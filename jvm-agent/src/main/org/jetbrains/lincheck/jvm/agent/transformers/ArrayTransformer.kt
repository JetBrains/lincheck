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
import org.jetbrains.lincheck.trace.TraceContext
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import org.objectweb.asm.commons.Method
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.ThreadDescriptor
import kotlin.reflect.KFunction
import java.lang.StringBuilder

/**
 *
 * [ArrayTransformer] tracks some array related method calls such as [newInstance] and [arraycopy],
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class ArrayTransformer(
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
        // STACK: elementClass, length
        invokeIfInAnalyzedCode(
            original = {
                visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            },
            instrumented = {
                // STACK: elementClass, length
                swap()
                // STACK: length, elementClass
                dup()
                // STACK: length, elementClass, elementClass
                val elementClass = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                // STACK: length, elementClass
                swap()
                // STACK: elementClass, length
                visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                // STACK: array
                dup()
                // STACK: array, array
                invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                // STACK: array, array, descriptor
                swap()
                loadLocal(elementClass)
                // STACK: array, array, descriptor, elementClass
                getElementTypeNameFromClass()
                // STACK: array, descriptor, array, elementName
                invokeStatic(Injections::afterObjectConstructor)
                // STACK: array
            }
        )
    }

    /**
     * Converts the class of the array elements into the canonical java string represetnation such that
     * it can be used by [Injections::afterObjectConstructor]
     * NOTE: Probably could be written as a regular method.
     *
     * Stack beore: ..., elementClass : Class<*>
     * Stack after: ..., elementClassName : String
     */
    private fun GeneratorAdapter.getElementTypeNameFromClass() {
        val getTypeFun: (Class<*>) -> Type = Type::getType
        val sbType = Type.getType(StringBuilder::class.java)
        val typeType = Type.getType(Type::class.java)
        val sbConstructor = Method.getMethod(StringBuilder::class.java.getDeclaredConstructor())
        val appendMethod = Method.getMethod(StringBuilder::class.java.getDeclaredMethod("append", String::class.java))
        val toStringMethod = Method.getMethod(StringBuilder::class.java.getDeclaredMethod("toString"))
        val getClassNameMethod = Method.getMethod(Type::class.java.getDeclaredMethod("getClassName"))

        // elementClass
        invokeStatic(getTypeFun as KFunction<*>)
        // elementType
        // NOTE: we use StringBuilder for Java8 compatibility
        newInstance(sbType)
        dup()
        // elementType, stringBuilder
        invokeConstructor(sbType, sbConstructor)
        // elementType, stringBuilder
        swap()
        // stringBuilder, elementType
        // NOTE: getClassName handles the annoying part where arrays elements are handled differently,
        //   than all the other types
        invokeVirtual(typeType, getClassNameMethod)
        // stringBuilder, elementString
        invokeVirtual(sbType, appendMethod)
        // stringBuilder
        push("[]")
        // stringBuilder, "[]"
        invokeVirtual(sbType, appendMethod)
        // stringBuilder,
        invokeVirtual(sbType, toStringMethod)
        // name
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
