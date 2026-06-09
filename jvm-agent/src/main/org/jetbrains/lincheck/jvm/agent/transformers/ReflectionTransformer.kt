/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.transformers

import org.objectweb.asm.MethodVisitor
import org.jetbrains.lincheck.jvm.agent.LincheckMethodVisitor
import org.jetbrains.lincheck.jvm.agent.MethodInformation
import org.jetbrains.lincheck.jvm.agent.invokeIfInAnalyzedCode
import org.jetbrains.lincheck.jvm.agent.invokeStatic
import org.jetbrains.lincheck.jvm.agent.storeArguments
import org.jetbrains.lincheck.trace.TraceContext
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.ThreadDescriptor
import sun.nio.ch.lincheck.ThreadDescriptor.getCurrentThreadDescriptor

/**
 *
 * [ReflectionTransformer] tracks some of the reflection method calls,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class ReflectionTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    context: TraceContext,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
    private val interceptArrayCopyMethod: Boolean = false,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        when {
            opcode == Opcodes.INVOKESTATIC && owner == "java/lang/reflect/Array" && name == "newInstance" ->
                visitInvokeArrayNewInstance(opcode, owner, name, desc, itf)

            opcode == Opcodes.INVOKESTATIC && owner == "java/lang/System" && name == "arraycopy" ->
                visitArrayCopyMethod(opcode, owner, name, desc, itf)

            else ->
                super.visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    private fun visitInvokeArrayNewInstance(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) = adapter.run {
        // TODO: should also call beforeNewObjectCreation?
        visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        // STACK: array
        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                dup()
                invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                invokeStatic(Injections::afterNewObjectCreation)
            }
        )
    }

    private fun visitArrayCopyMethod(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (!interceptArrayCopyMethod) {
            visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        // STACK: srcArray, srcPos, dstArray, dstPos, length
        invokeIfInAnalyzedCode(
            original = {
                visitMethodInsn(opcode, owner, name, desc, itf)
            },
            instrumented = {
                // STACK: srcArray, srcPos, dstArray, dstPos, length
                val threadDescriptorLocal = newLocal(OBJECT_TYPE).also {
                    invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
                    storeLocal(it)
                }
                loadLocal(threadDescriptorLocal)
                // STACK: srcArray, srcPos, dstArray, dstPos, length, descriptor
                invokeStatic(Injections::onArrayCopy)
            }
        )
    }

}
