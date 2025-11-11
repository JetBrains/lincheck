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
import org.jetbrains.lincheck.trace.TRACE_CONTEXT
import org.jetbrains.lincheck.trace.isThisAccess
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
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
    methodInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
    configuration: TransformationConfiguration,
) : MethodCallTransformerBase(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor, configuration) {

    override fun processMethodCall(desc: String, opcode: Int, owner: String, name: String, itf: Boolean) = adapter.run {
        val isConstructorCall = (name == "<init>")
        val receiverType = getType("L$owner;")
        val argumentNames = getArgumentNames(desc, opcode)
        val ownerName = when {
            opcode == INVOKESTATIC && name.endsWith($$"$default") &&
                    argumentNames?.firstOrNull()?.locations?.singleOrNull()?.isThisAccess() == true -> argumentNames[0]
            else -> getOwnerName(desc, opcode)
        }

        // We assume that constructors return an object even though they don't
        // (because their descriptor specifies the 'void' return type).
        // Later 'processMethodCallReturn' takes this object and reports it as "return value" of a constructor.
        //
        // This assumption holds because the constructor invocation bytecode sequence looks as follows:
        // `NEW Clazz; DUP; push args; INVOKESPECIAL Clazz.<init>`.
        //
        // Before invoking the `<init>` method, the stack has the following shape:
        //   `STACK: this (uninitialized), this (uninitialized), args`
        // and after its invocation:
        //    `STACK: this (initialized)`.
        val returnType = if (isConstructorCall) receiverType else getReturnType(desc)

        // STACK: receiver?, arguments
        val argumentLocals = storeArguments(desc)
        val argumentsArrayLocal = newLocal(OBJECT_ARRAY_TYPE).also {
            pushArray(argumentLocals)
            storeLocal(it)
        }
        val receiverLocal = when {
            (opcode != INVOKESTATIC && !isConstructorCall) -> newLocal(receiverType).also { storeLocal(it) }
            else -> null
        }
        val sanitizedMethodName = sanitizeMethodName(owner, name, InstrumentationMode.TRACE_RECORDING)
        val methodId = TRACE_CONTEXT.getOrCreateMethodId(owner.toCanonicalClassName(), sanitizedMethodName, Types.convertAsmMethodType(desc))

        val threadDescriptorLocal = newLocal(OBJECT_TYPE).also {
            invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
            storeLocal(it)
        }

        // STACK: <empty>
        processMethodCallEnter(methodId, receiverLocal, argumentsArrayLocal, ownerName, argumentNames, threadDescriptorLocal)
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
                    threadDescriptorLocal,
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
                    argumentsArrayLocal,
                    threadDescriptorLocal,
                )
            }
        )
    }
}