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

import org.jetbrains.kotlinx.lincheck.canonicalClassName
import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.kotlinx.lincheck.transformation.transformers.DeterministicInvokeDynamicTransformer.Companion.advancingCounter
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
        if (isCoroutineInternalClass(owner)) {
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
        // STACK [INVOKEVIRTUAL]: owner, arguments
        // STACK [INVOKESTATIC] :        arguments
        val argumentLocals = storeArguments(desc)
        val receiver = if (opcode == INVOKESTATIC) null else newLocal(getType("L$owner;"))
        receiver?.let { storeLocal(it) }
        // STACK : <empty>
        when (receiver) {
            null -> pushNull()
            else -> loadLocal(receiver)
        }
        push(owner.canonicalClassName)
        push(name)
        loadNewCodeLocationId()
        // STACK [INVOKEVIRTUAL]: owner, owner, className, methodName, codeLocation
        // STACK [INVOKESTATIC]:         null, className, methodName, codeLocation
        adapter.push(MethodIds.getMethodId(owner, name, desc))
        // STACK [INVOKEVIRTUAL]: owner, owner, className, methodName, codeLocation, methodId
        // STACK [INVOKESTATIC]:         null, className, methodName, codeLocation, methodId
        adapter.push(desc)
        // STACK [INVOKEVIRTUAL]: owner, owner, className, methodName, codeLocation, methodId, methodDesc
        // STACK [INVOKESTATIC]:         null, className, methodName, codeLocation, methodId, methodDesc
        pushArray(argumentLocals)
        // STACK: ..., argumentsArray
        val argumentsArrayLocal = newLocal(getType("[Ljava/lang/Object;"))
        storeLocal(argumentsArrayLocal)
        loadLocal(argumentsArrayLocal)
        invokeStatic(Injections::onMethodCall)
        
        val deterministicMethodDescriptor = newLocal(OBJECT_TYPE)
        storeLocal(deterministicMethodDescriptor)
        
        val deterministicCallIdLocal = newLocal(LONG_TYPE)
        pushDeterministicCallId(deterministicMethodDescriptor)
        storeLocal(deterministicCallIdLocal)
        
        invokeBeforeEventIfPluginEnabled("method call $methodName", setMethodEventId = true)
        
        advancingCounter {
            tryCatchFinally(
                tryBlock = {
                    val returnType = getReturnType(desc)
                    invokeMethodOrDeterministicCall(
                        deterministicMethodDescriptor,
                        deterministicCallIdLocal,
                        returnType,
                        receiver,
                        argumentsArrayLocal
                    ) {
                        // STACK: <empty>
                        receiver?.let { loadLocal(it) }
                        loadLocals(argumentLocals)
                        // STACK [INVOKEVIRTUAL]: owner, arguments
                        // STACK [INVOKESTATIC] :        arguments
                        visitMethodInsn(opcode, owner, name, desc, itf)
                        // STACK: result/<empty> for void
                    }

                    // STACK: result/<empty> for void
                    processMethodCallResult(
                        deterministicCallIdLocal, deterministicMethodDescriptor, desc, receiver, argumentsArrayLocal
                    )
                    // STACK: result/<empty> for void
                },
                catchBlock = {
                    val exceptionLocal = newLocal(getType(Throwable::class.java))
                    storeLocal(exceptionLocal)
                    // STACK: <empty>
                    loadLocal(deterministicCallIdLocal)
                    loadLocal(deterministicMethodDescriptor)
                    if (receiver == null) pushNull() else loadLocal(receiver)
                    loadLocal(argumentsArrayLocal)
                    loadLocal(exceptionLocal)
                    // STACK: deterministicCallId, deterministicMethodDescriptor, receiver, params, exception
                    invokeStatic(Injections::onMethodCallException)
                    // STACK: <empty>
                    loadLocal(exceptionLocal)
                    throwException()
                }
            )
        }
    }
    
    private fun GeneratorAdapter.pushDeterministicCallId(deterministicMethodDescriptor: Int) {
        if (!isInTraceDebuggerMode) {
            push(0L)
            return
        }
        val elseIf = newLabel()
        val endIf = newLabel()
        loadLocal(deterministicMethodDescriptor)
        ifNull(elseIf)
        getStatic(traceDebuggerTrackerEnumType, NativeMethodCall.name, traceDebuggerTrackerEnumType)
        invokeStatic(Injections::getNextTraceDebuggerEventTrackerId)
        goTo(endIf)
        visitLabel(elseIf)
        push(0L)
        visitLabel(endIf)
    }
    
    private fun GeneratorAdapter.invokeMethodOrDeterministicCall(
        deterministicMethodDescriptor: Int, deterministicCallIdLocal: Int, returnType: Type,
        receiverLocal: Int?, parametersLocal: Int,
        invokeDefault: GeneratorAdapter.() -> Unit,
    ) {
        val onDefaultMethodCall = newLabel()
        val endIf = newLabel()
        // STACK: <empty>
        loadLocal(deterministicMethodDescriptor)
        ifNull(onDefaultMethodCall) // If not deterministic call, we just call it regularly
        // STACK: <empty>
        invokeInIgnoredSection {
            loadLocal(deterministicCallIdLocal)
            loadLocal(deterministicMethodDescriptor)
            if (receiverLocal == null) pushNull() else loadLocal(receiverLocal)
            loadLocal(parametersLocal)
            // STACK: deterministicCallId, deterministicMethodDescriptor, receiver, parameters
            invokeStatic(Injections::invokeDeterministicallyOrNull)
            // STACK: JavaResult
            val result = newLocal(getType(BootstrapResult::class.java))
            storeLocal(result)
            // STACK: <empty>
            loadLocal(result)
            // STACK: JavaResult
            ifNull(onDefaultMethodCall)
            // STACK: <empty>
            loadLocal(result)
            // STACK: JavaResult
            invokeStatic(Injections::getFromOrThrow)
            // STACK: Object
            if (returnType == VOID_TYPE) pop() else unbox(returnType)
        }
        goTo(endIf)
        visitLabel(onDefaultMethodCall)
        // STACK: <empty>
        invokeDefault()
        // STACK: <returnType>/<empty> (for void)
        visitLabel(endIf)
    }

    private fun processMethodCallResult(
        deterministicCallIdLocal: Int,
        deterministicMethodDescriptorLocal: Int,
        desc: String,
        receiver: Int?,
        argumentsArrayLocal: Int
    ) = adapter.run {
        // STACK: result?
        val resultType = Type.getReturnType(desc)
        if (resultType == VOID_TYPE) {
            // STACK: <empty>
            loadLocal(deterministicCallIdLocal)
            loadLocal(deterministicMethodDescriptorLocal)
            if (receiver == null) pushNull() else loadLocal(receiver)
            loadLocal(argumentsArrayLocal)
            invokeStatic(Injections::onMethodCallReturnVoid)
            // STACK: <empty>
        } else {
            // STACK: result
            val resultLocal = newLocal(resultType)
            storeLocal(resultLocal)
            loadLocal(deterministicCallIdLocal)
            loadLocal(deterministicMethodDescriptorLocal)
            if (receiver == null) pushNull() else loadLocal(receiver)
            loadLocal(argumentsArrayLocal)
            loadLocal(resultLocal)
            box(resultType)
            invokeStatic(Injections::onMethodCallReturn)
            loadLocal(resultLocal)
            // STACK: result
        }
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