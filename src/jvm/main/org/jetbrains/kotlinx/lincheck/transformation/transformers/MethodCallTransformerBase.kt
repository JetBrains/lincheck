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

import org.jetbrains.kotlinx.lincheck.traceagent.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.*
import sun.nio.ch.lincheck.TraceDebuggerTracker.NativeMethodCall

/**
 * [MethodCallTransformerBase] tracks method calls,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal abstract class MethodCallTransformerBase(
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

    protected abstract fun processMethodCall(desc: String, opcode: Int, owner: String, name: String, itf: Boolean)

    protected fun GeneratorAdapter.pushReceiver(receiverLocal: Int?) {
        // STACK: <empty>
        if (receiverLocal != null) {
            loadLocal(receiverLocal)
        } else {
            pushNull()
        }
        // STACK: receiver?
    }

    protected fun GeneratorAdapter.processMethodCallReturn(
        className: String,
        methodName: String,
        returnType: Type,
        methodId: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
        deterministicCallIdLocal: Int? = null,
        deterministicMethodDescriptorLocal: Int? = null
    ) {
        // STACK: result?
        val resultLocal = when {
            (returnType == VOID_TYPE) -> null
            else -> newLocal(returnType).also { storeLocal(it) }
        }
        push(className.toCanonicalClassName())
        push(methodName)
        if (deterministicCallIdLocal != null) {
            loadLocal(deterministicCallIdLocal)
        } else {
            push(0L)
        }
        if (deterministicMethodDescriptorLocal != null) {
            loadLocal(deterministicMethodDescriptorLocal)
        } else {
            pushNull()
        }
        push(methodId)
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        resultLocal?.let {
            loadLocal(it)
            box(returnType)
        }
        // STACK: className, methodName, deterministicCallId, deterministicMethodDescriptor, methodId, receiver, arguments, result?
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

    protected fun GeneratorAdapter.processMethodCallException(
        className: String,
        methodName: String,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
        deterministicCallIdLocal: Int? = null,
        deterministicMethodDescriptorLocal: Int? = null
    ) {
        // STACK: exception
        val exceptionLocal = newLocal(THROWABLE_TYPE)
        storeLocal(exceptionLocal)
        // STACK: <empty>
        push(className.toCanonicalClassName())
        push(methodName)
        if (deterministicCallIdLocal != null) {
            loadLocal(deterministicCallIdLocal)
        } else {
            push(0L)
        }
        if (deterministicMethodDescriptorLocal != null) {
            loadLocal(deterministicMethodDescriptorLocal)
        } else {
            pushNull()
        }
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        loadLocal(exceptionLocal)
        // STACK: className, methodName, deterministicCallId, deterministicMethodDescriptor, receiver, params, exception
        invokeStatic(Injections::onMethodCallException)
        // STACK: Throwable
        throwException()
    }

    protected fun isIgnoredMethod(className: String) =
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
    protected fun isCoroutineResumptionSyntheticAccessor(className: String, methodName: String): Boolean =
        (this.methodName == "invokeSuspend") && methodName.startsWith("access\$")
}