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
        val endLabel = newLabel()
        val methodCallStartLabel = newLabel()
        // STACK [INVOKEVIRTUAL]: owner, arguments
        // STACK [INVOKESTATIC] :        arguments
        val argumentLocals = storeArguments(desc)
        // STACK [INVOKEVIRTUAL]: owner
        // STACK [INVOKESTATIC] : <empty>
        when (opcode) {
            INVOKESTATIC -> visitInsn(ACONST_NULL)
            else -> dup()
        }
        push(owner)
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
        invokeStatic(Injections::onMethodCall)
        val deterministicMethodDescriptor = newLocal(OBJECT_TYPE)
        storeLocal(deterministicMethodDescriptor)
        
        invokeBeforeEventIfPluginEnabled("method call $methodName", setMethodEventId = true)
        // STACK [INVOKEVIRTUAL]: owner
        // STACK [INVOKESTATIC] :
        val methodCallEndLabel = newLabel()
        val handlerExceptionStartLabel = newLabel()
        visitTryCatchBlock(methodCallStartLabel, methodCallEndLabel, handlerExceptionStartLabel, null)
        visitLabel(methodCallStartLabel)
        
        val regularMethodCallLabel = newLabel()
        val endIfLabel = newLabel()
        loadLocal(deterministicMethodDescriptor)
        ifNull(regularMethodCallLabel)
        // STACK [INVOKEVIRTUAL]: owner
        // STACK [INVOKESTATIC] :
        if (opcode != INVOKESTATIC) {
            pop()
        }
        // STACK [INVOKEVIRTUAL]:
        // STACK [INVOKESTATIC] :
        val returnType = getReturnType(desc)
        invokeDeterministicCall(deterministicMethodDescriptor, returnType)
        goTo(endIfLabel)
        visitLabel(regularMethodCallLabel)
        // STACK [INVOKEVIRTUAL]: owner
        // STACK [INVOKESTATIC] :
        loadLocals(argumentLocals)
        // STACK [INVOKEVIRTUAL]: owner, arguments
        // STACK [INVOKESTATIC] :        arguments
        visitMethodInsn(opcode, owner, name, desc, itf)
        visitLabel(endIfLabel)
        visitLabel(methodCallEndLabel)
        
        // STACK [INVOKEVIRTUAL]: owner, arguments
        // STACK [INVOKESTATIC] :        arguments
        processMethodCallResult(desc)
        // STACK: result
        goTo(endLabel)
        visitLabel(handlerExceptionStartLabel)
        dup()
        invokeStatic(Injections::onMethodCallException)
        throwException()
        visitLabel(endLabel)
        // STACK: result
    }

    private fun GeneratorAdapter.invokeDeterministicCall(
        deterministicMethodDescriptor: Int,
        returnType: Type?
    ) {
        invokeInIgnoredSection {
            if (isInTraceDebuggerMode) {
                getStatic(traceDebuggerTrackerEnumType, NativeMethodCall.name, traceDebuggerTrackerEnumType)
                invokeStatic(Injections::getNextTraceDebuggerEventTrackerId)
                loadLocal(deterministicMethodDescriptor)
                invokeStatic(Injections::invokeDeterministicCallDescriptorInTraceDebugger)
            } else {
                loadLocal(deterministicMethodDescriptor)
                invokeStatic(Injections::invokeDeterministicCallDescriptorInLincheck)
            }
            if (returnType == VOID_TYPE) {
                pop()
            } else {
                unbox(returnType)
            }
        }
    }

    private fun processMethodCallResult(desc: String) = adapter.run {
        // STACK: result?
        val resultType = Type.getReturnType(desc)
        if (resultType == VOID_TYPE) {
            // STACK: <empty>
            invokeStatic(Injections::onMethodCallReturnVoid)
            // STACK: <empty>
        } else {
            // STACK: result
            val resultLocal = newLocal(resultType)
            copyLocal(resultLocal)
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
        className == "java/util/Properties"

    @Suppress("UNUSED_PARAMETER")
    private fun isCoroutineResumptionSyntheticAccessor(className: String, methodName: String): Boolean =
        (this.methodName == "invokeSuspend") && methodName.startsWith("access\$")

    companion object {
        private val traceDebuggerTrackerEnumType: Type = getType(TraceDebuggerTracker::class.java)
    }
}