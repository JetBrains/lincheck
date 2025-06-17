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
import org.jetbrains.kotlinx.lincheck.tracedata.MethodDescriptor
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
) : MethodCallTransformerBase(fileName, className, methodName, adapter) {
    override fun processMethodCall(desc: String, opcode: Int, owner: String, name: String, itf: Boolean) = adapter.run {
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

    companion object {
        private val traceDebuggerTrackerEnumType: Type = getType(TraceDebuggerTracker::class.java)
    }
}