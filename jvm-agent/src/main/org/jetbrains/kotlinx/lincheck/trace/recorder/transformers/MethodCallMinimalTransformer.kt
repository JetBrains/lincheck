/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.trace.recorder.transformers

import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.kotlinx.lincheck.transformation.transformers.MethodCallTransformerBase
import org.jetbrains.lincheck.trace.TRACE_CONTEXT
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import sun.nio.ch.lincheck.*

/**
 * [MethodCallMinimalTransformer] tracks method calls,
 * injecting invocations of corresponding [EventTracker] methods.
 *
 *
 * *Note*:
 *
 * [MethodCallMinimalTransformer] and [ObjectCreationMinimalTransformer] transformers are part of the `jvm-agent` subproject,
 * and not the `trace-recorder` one, because they are not used directly by trace-recorder, but rather
 * by the lincheck's [LincheckClassVisitor].
 */
internal class MethodCallMinimalTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    metaInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : MethodCallTransformerBase(fileName, className, methodName, descriptor, access, metaInfo, adapter, methodVisitor) {

    override fun processMethodCall(desc: String, opcode: Int, owner: String, name: String, itf: Boolean) = adapter.run {
        val receiverType = getType("L$owner;")
        val returnType = getReturnType(desc)
        val ownerName = ownerNameAnalyzer?.stack?.let { stack ->
            if (opcode == INVOKESTATIC) return@let null
            val position = getArgumentTypes(desc).sumOf { it.size }
            stack.getStackElementAt(position)
        }
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
        val methodId = TRACE_CONTEXT.getOrCreateMethodId(owner.toCanonicalClassName(), name, desc)
        // STACK: <empty>
        processMethodCallEnter(methodId, receiverLocal, argumentsArrayLocal, ownerName)
        // STACK: deterministicCallDescriptor
        pop()
        // STACK: <empty>
        tryCatchFinally(
            tryBlock = {
                // STACK: <empty>
                receiverLocal?.let { loadLocal(it) }
                loadLocals(argumentLocals)
                // STACK: receiver?, arguments
                mv.visitMethodInsn(opcode, owner, name, desc, itf)
                // STACK: result?
                processMethodCallReturn(
                    returnType,
                    null,
                    null,
                    methodId,
                    receiverLocal,
                    argumentsArrayLocal,
                )
                // STACK: result?
            },
            catchBlock = {
                // STACK: exception
                processMethodCallException(
                    null,
                    null,
                    methodId,
                    receiverLocal,
                    argumentsArrayLocal
                )
            }
        )
    }
}