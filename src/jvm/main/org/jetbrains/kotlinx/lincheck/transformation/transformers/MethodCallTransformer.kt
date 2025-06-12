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
import org.jetbrains.kotlinx.lincheck.util.MethodDescriptor
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
            invokeInsideIgnoredSection {
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
        invokeIfInAnalyzedCode(
            original = {
                visitMethodInsn(opcode, owner, name, desc, itf)
            },
            instrumented = {
                processMethodCall(desc, opcode, owner, name, itf)
            }
        )
    }

    private fun isThreadLocalRandomCurrent(owner: String, methodName: String): Boolean {
        return owner == "java/util/concurrent/ThreadLocalRandom" && methodName == "current"
    }

    private fun GeneratorAdapter.processMethodCall(desc: String, opcode: Int, owner: String, name: String, itf: Boolean) {
        val receiverType = Type.getType("L$owner;")
        val returnType = getReturnType(desc)
        // STACK: receiver?, arguments
        val argumentLocals = storeArguments(desc)
        val argumentsArrayLocal = newLocal(OBJECT_ARRAY_TYPE).also {
            pushArray(argumentLocals)
            storeLocal(it)
        }
        val receiverLocal = when {
            (opcode != INVOKESTATIC) -> newLocal(receiverType).also { storeLocal(it) }
            else -> null
        }
        val methodId = methodCache.getOrCreateId(MethodDescriptor(owner.toCanonicalClassName(), name, desc))
        // STACK: <empty>
        processMethodCallEnter(methodId, receiverLocal, argumentsArrayLocal)
        // STACK: deterministicCallDescriptor
        val deterministicMethodDescriptorLocal = newLocal(OBJECT_TYPE)
            .also { storeLocal(it) }
        // STACK: <empty>
        pushDeterministicCallId(deterministicMethodDescriptorLocal)
        val deterministicCallIdLocal = newLocal(LONG_TYPE)
            .also { storeLocal(it) }
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
                    methodId,
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
                    methodId,
                    receiverLocal,
                    argumentsArrayLocal,
                )
            }
        )
    }

    private fun GeneratorAdapter.processMethodCallEnter(
        methodId: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int
    ) {
        // STACK: <empty>
        loadNewCodeLocationId()
        // STACK: codeLocation
        push(methodId)
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        // STACK: codeLocation, methodId, receiver?, argumentsArray
        invokeStatic(Injections::onMethodCall)
        // STACK: deterministicCallDescriptor
        invokeBeforeEventIfPluginEnabled("method call ${this@MethodCallTransformer.methodName}", setMethodEventId = true)
    }

    private fun GeneratorAdapter.pushDeterministicCallId(deterministicMethodDescriptorLocal: Int) {
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

    private fun GeneratorAdapter.invokeMethodOrDeterministicCall(
        returnType: Type,
        deterministicCallIdLocal: Int,
        deterministicMethodDescriptorLocal: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
        invokeDefault: GeneratorAdapter.() -> Unit,
    ) {
        val onDefaultMethodCallLabel = newLabel()
        val endIfLabel = newLabel()
        // STACK: <empty>
        loadLocal(deterministicMethodDescriptorLocal)
        ifNull(onDefaultMethodCallLabel) // If not deterministic call, we just call it regularly

        // STACK: <empty>
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
        // STACK: result?
        if (returnType == VOID_TYPE) pop() else unbox(returnType)
        // STACK: boxedResult?
        goTo(endIfLabel)

        visitLabel(onDefaultMethodCallLabel)
        // STACK: <empty>
        invokeDefault()
        // STACK: result?
        visitLabel(endIfLabel)
    }

    private fun GeneratorAdapter.processMethodCallReturn(
        returnType: Type,
        deterministicCallIdLocal: Int,
        deterministicMethodDescriptorLocal: Int,
        methodId: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int
    ) {
        // STACK: result?
        val resultLocal = when {
            (returnType == VOID_TYPE) -> null
            else -> newLocal(returnType).also { storeLocal(it) }
        }
        loadLocal(deterministicCallIdLocal)
        loadLocal(deterministicMethodDescriptorLocal)
        push(methodId)
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        resultLocal?.let {
            loadLocal(it)
            box(returnType)
        }
        // STACK: deterministicCallId, deterministicMethodDescriptor, methodId, receiver, arguments, result?
        when {
            returnType == VOID_TYPE -> invokeStatic(Injections::onMethodCallReturnVoid)
            else                    -> {
                invokeStatic(Injections::onMethodCallReturn)
                // STACK: boxedResult
                unbox(returnType)
                // STACK: result
            }
        }
        // STACK: result?
    }

    private fun GeneratorAdapter.processMethodCallException(
        deterministicCallIdLocal: Int,
        deterministicMethodDescriptorLocal: Int,
        methodId: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
    ) {
        // STACK: exception
        val exceptionLocal = newLocal(THROWABLE_TYPE)
        storeLocal(exceptionLocal)
        // STACK: <empty>
        loadLocal(deterministicCallIdLocal)
        loadLocal(deterministicMethodDescriptorLocal)
        push(methodId)
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        loadLocal(exceptionLocal)
        // STACK: deterministicCallId, deterministicMethodDescriptor, methodId, receiver, params, exception
        invokeStatic(Injections::onMethodCallException)
        // STACK: Throwable
        throwException()
    }

    private fun GeneratorAdapter.pushReceiver(receiverLocal: Int?) {
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