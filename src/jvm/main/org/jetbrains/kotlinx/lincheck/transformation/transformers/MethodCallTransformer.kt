/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation.transformers

import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.*
import sun.nio.ch.lincheck.TraceDebuggerTracker.NativeMethodCall

/**
 * [MethodCallTransformer] tracks method calls,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class MethodCallTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        // TODO: do not ignore <init>
        if (name == "<init>" || isIgnoredMethod(className = owner)) {
            visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        if (isCoroutineInternalClass(owner.toCanonicalClassName())) {
            invokeInIgnoredSection {
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
            return
        }
        if (isCoroutineResumptionSyntheticAccessor(owner, name)) {
            visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        // It is useless for the user, and it depends on static initialization which is not instrumented.
        if (isThreadLocalRandomCurrent(owner, name)) {
            visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        invokeIfInTestingCode(
            original = {
                visitMethodInsn(opcode, owner, name, desc, itf)
            },
            code = {
                processMethodCall(desc, opcode, owner, name, itf)
            }
        )
    }

    private fun isThreadLocalRandomCurrent(owner: String, methodName: String): Boolean {
        return owner == "java/util/concurrent/ThreadLocalRandom" && methodName == "current"
    }

    private fun processMethodCall(desc: String, opcode: Int, owner: String, name: String, itf: Boolean) = adapter.run {
        val receiverType = Type.getType("L$owner;")
        val returnType = getReturnType(desc)
        // STACK: receiver?, arguments
        val argumentLocals = storeArguments(desc)
        val argumentsArrayLocal = newLocal(OBJECT_TYPE).also {
            pushArray(argumentLocals)
            storeLocal(it)
        }
        val receiverLocal = when {
            (opcode != INVOKESTATIC) -> newLocal(receiverType).also { storeLocal(it) }
            else -> null
        }
        // STACK: <empty>
        processMethodCallEnter(desc, owner, name, receiverLocal, argumentsArrayLocal)
        // STACK: deterministicCallDescriptor
        val deterministicMethodDescriptorLocal = newLocal(OBJECT_TYPE)
        val deterministicCallIdLocal = newLocal(LONG_TYPE)
        storeLocal(deterministicMethodDescriptorLocal)
        pushDeterministicCallId(deterministicMethodDescriptorLocal)
        storeLocal(deterministicCallIdLocal)
        // STACK: <empty>
        tryCatchFinally(
            tryBlock = {
                invokeMethodOrDeterministicCall(
                    returnType,
                    deterministicCallIdLocal,
                    deterministicMethodDescriptorLocal,
                    receiverLocal,
                    argumentsArrayLocal,
                ) {
                    // STACK: <empty>
                    receiverLocal?.let { loadLocal(it) }
                    loadLocals(argumentLocals)
                    // STACK: receiver?, arguments
                    visitMethodInsn(opcode, owner, name, desc, itf)
                    // STACK: result?
                }
                // STACK: result?
                processMethodCallReturn(
                    returnType,
                    deterministicCallIdLocal,
                    deterministicMethodDescriptorLocal,
                    receiverLocal,
                    argumentsArrayLocal,
                )
                // STACK: result?
            },
            catchBlock = {
                // STACK: exception
                processMethodCallException(
                    deterministicCallIdLocal,
                    deterministicMethodDescriptorLocal,
                    receiverLocal,
                    argumentsArrayLocal,
                )
            }
        )
    }

    private fun processMethodCallEnter(
        desc: String,
        owner: String,
        name: String,
        receiverLocal: Int?,
        argumentsArrayLocal: Int
    ) = adapter.run {
        // STACK: <empty>
        pushReceiver(receiverLocal)
        push(owner.toCanonicalClassName())
        push(name)
        loadNewCodeLocationId()
        // STACK: receiver?, className, methodName, codeLocation
        push(MethodIds.getMethodId(owner, name, desc))
        push(desc)
        // STACK: receiver?, className, methodName, codeLocation, methodId, methodDesc
        loadLocal(argumentsArrayLocal)
        // STACK: receiver?, className, methodName, codeLocation, methodId, methodDesc, argumentsArray
        invokeStatic(Injections::onMethodCall)
        // STACK: deterministicCallDescriptor
        invokeBeforeEventIfPluginEnabled("method call $methodName", setMethodEventId = true)
    }

    private fun pushDeterministicCallId(deterministicMethodDescriptorLocal: Int) = adapter.run {
        if (!isInTraceDebuggerMode) {
            push(0L)
            return
        }
        val elseIf = newLabel()
        val endIf = newLabel()
        loadLocal(deterministicMethodDescriptorLocal)
        ifNull(elseIf)
        getStatic(traceDebuggerTrackerEnumType, NativeMethodCall.name, traceDebuggerTrackerEnumType)
        invokeStatic(Injections::getNextTraceDebuggerEventTrackerId)
        goTo(endIf)
        visitLabel(elseIf)
        push(0L)
        visitLabel(endIf)
    }

    private fun invokeMethodOrDeterministicCall(
        returnType: Type,
        deterministicCallIdLocal: Int,
        deterministicMethodDescriptorLocal: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
        invokeDefault: GeneratorAdapter.() -> Unit,
    ) = adapter.run {
        val onDefaultMethodCallLabel = newLabel()
        val endIfLabel = newLabel()
        // STACK: <empty>
        loadLocal(deterministicMethodDescriptorLocal)
        ifNull(onDefaultMethodCallLabel) // If not deterministic call, we just call it regularly
        // STACK: <empty>
        invokeInIgnoredSection {
            loadLocal(deterministicCallIdLocal)
            loadLocal(deterministicMethodDescriptorLocal)
            pushReceiver(receiverLocal)
            loadLocal(argumentsArrayLocal)
            // STACK: deterministicCallId, deterministicMethodDescriptor, receiver, parameters
            invokeStatic(Injections::invokeDeterministicallyOrNull)
            // STACK: BootstrapResult
            val resultLocal = newLocal(getType(BootstrapResult::class.java))
            storeLocal(resultLocal)
            // STACK: <empty>
            loadLocal(resultLocal)
            // STACK: BootstrapResult
            ifNull(onDefaultMethodCallLabel)
            // STACK: <empty>
            loadLocal(resultLocal)
            // STACK: BootstrapResult
            invokeStatic(Injections::getFromOrThrow)
            // STACK: result
            if (returnType == VOID_TYPE) pop() else unbox(returnType)
        }
        goTo(endIfLabel)
        visitLabel(onDefaultMethodCallLabel)
        // STACK: <empty>
        invokeDefault()
        // STACK: result?
        visitLabel(endIfLabel)
    }

    private fun processMethodCallReturn(
        returnType: Type,
        deterministicCallIdLocal: Int,
        deterministicMethodDescriptorLocal: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int
    ) = adapter.run {
        // STACK: result?
        val resultLocal = when {
            (returnType == VOID_TYPE) -> null
            else -> newLocal(returnType).also { storeLocal(it) }
        }
        loadLocal(deterministicCallIdLocal)
        loadLocal(deterministicMethodDescriptorLocal)
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        resultLocal?.let {
            loadLocal(it)
            box(returnType)
        }
        // STACK: deterministicCallId, deterministicMethodDescriptor, receiver, arguments, result?
        when {
            (returnType == VOID_TYPE) -> invokeStatic(Injections::onMethodCallReturnVoid)
            else                      -> invokeStatic(Injections::onMethodCallReturn)
        }
        resultLocal?.let { loadLocal(it) }
        // STACK: result?
    }

    private fun processMethodCallException(
        deterministicCallIdLocal: Int,
        deterministicMethodDescriptorLocal: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
    ) = adapter.run {
        // STACK: exception
        val exceptionLocal = newLocal(THROWABLE_TYPE)
        storeLocal(exceptionLocal)
        // STACK: <empty>
        loadLocal(deterministicCallIdLocal)
        loadLocal(deterministicMethodDescriptorLocal)
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        loadLocal(exceptionLocal)
        // STACK: deterministicCallId, deterministicMethodDescriptor, receiver, params, exception
        invokeStatic(Injections::onMethodCallException)
        // STACK: <empty>
        loadLocal(exceptionLocal)
        throwException()
    }

    private fun pushReceiver(receiverLocal: Int?) = adapter.run {
        // STACK: <empty>
        if (receiverLocal != null) {
            loadLocal(receiverLocal)
        } else {
            pushNull()
        }
        // STACK: receiver?
    }

    private fun isIgnoredMethod(className: String) =
        className.startsWith("sun/nio/ch/lincheck/") ||
        className.startsWith("org/jetbrains/kotlinx/lincheck/") ||
        className == "kotlin/jvm/internal/Intrinsics" ||
        className == "java/util/Objects" ||
        className == "java/lang/String" ||
        className == "java/lang/Boolean" ||
        className == "java/lang/Long" ||
        className == "java/lang/Integer" ||
        className == "java/lang/Short" ||
        className == "java/lang/Byte" ||
        className == "java/lang/Double" ||
        className == "java/lang/Float" ||
        className == "java/util/Locale" ||
        className == "org/slf4j/helpers/Util" ||
        className == "java/util/Properties" ||
        className == "java/lang/invoke/MethodHandles"

    @Suppress("UNUSED_PARAMETER")
    private fun isCoroutineResumptionSyntheticAccessor(className: String, methodName: String): Boolean =
        (this.methodName == "invokeSuspend") && methodName.startsWith("access\$")

    companion object {
        private val traceDebuggerTrackerEnumType: Type = getType(TraceDebuggerTracker::class.java)
    }
}