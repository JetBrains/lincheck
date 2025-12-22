/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.transformers

import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.jvm.agent.LincheckJavaAgent.instrumentationMode
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode.TRACE_DEBUGGING
import org.jetbrains.lincheck.trace.TraceContext
import org.objectweb.asm.MethodVisitor
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
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    context: TraceContext,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
    configuration: TransformationConfiguration,
) : MethodCallTransformerBase(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor, configuration) {

    override fun processMethodCall(desc: String, opcode: Int, owner: String, name: String, itf: Boolean) = adapter.run {
        invokeIfInAnalyzedCode(
            original = {
                mv.visitMethodInsn(opcode, owner, name, desc, itf)
            },
            instrumented = {
                processInstrumentedMethodCall(desc, opcode, owner, name, itf)
            }
        )
    }

    private fun processInstrumentedMethodCall(desc: String, opcode: Int, owner: String, name: String, itf: Boolean) = adapter.run {
        val receiverType = getType("L$owner;")
        val returnType = getReturnType(desc)
        val ownerName = getOwnerName(desc, opcode)
        val argumentNames = getArgumentNames(desc, opcode)
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
        val methodId = context.getOrCreateMethodId(owner.toCanonicalClassName(), name, Types.convertAsmMethodType(desc))

        val threadDescriptorLocal = newLocal(OBJECT_TYPE).also {
            invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
            storeLocal(it)
        }

        // STACK: <empty>
        processMethodCallEnter(methodId, receiverLocal, argumentsArrayLocal, ownerName, argumentNames, threadDescriptorLocal)
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
                    mv.visitMethodInsn(opcode, owner, name, desc, itf)
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
                    threadDescriptorLocal,
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
                    threadDescriptorLocal,
                )
            }
        )
    }

    private fun GeneratorAdapter.pushDeterministicCallId(deterministicMethodDescriptorLocal: Int) {
        if (instrumentationMode != TRACE_DEBUGGING) {
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
        invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
        loadLocal(deterministicCallIdLocal)
        loadLocal(deterministicMethodDescriptorLocal)
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        // STACK: descriptor, deterministicCallId, deterministicMethodDescriptor, receiver, parameters
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