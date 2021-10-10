/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.nvm

import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.ASM_API
import org.jetbrains.kotlinx.lincheck.annotations.DurableRecoverAll
import org.jetbrains.kotlinx.lincheck.annotations.DurableRecoverPerThread
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.objectweb.asm.*
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import kotlin.reflect.jvm.javaMethod

private const val RECOVER_DESCRIPTOR = "()V"
private const val RECOVER_ALL_GENERATED_NAME = "__\$rec*verAll\$__"
private const val RECOVER_ALL_GENERATED_ACCESS =
    Opcodes.ACC_PRIVATE or Opcodes.ACC_SYNCHRONIZED or Opcodes.ACC_SYNTHETIC
private val NVM_STATE_HOLDER_TYPE = Type.getType(NVMStateHolder::class.java)
private val IS_CRASHED_METHOD = Method.getMethod(NVMStateHolder::isCrashed.javaMethod)
private val RESET_ALL_CRASHED_METHOD = Method.getMethod(NVMStateHolder::resetAllCrashed.javaMethod)
private val OPERATION_TYPE = Type.getType(Operation::class.java)

internal class DurableOperationRecoverTransformer(cv: ClassVisitor, private val testClass: Class<*>) : ClassVisitor(ASM_API, cv) {
    private var shouldTransform = false
    internal val recoverAllMethod: java.lang.reflect.Method?
    internal val recoverPerThreadMethod: java.lang.reflect.Method?
    internal lateinit var name: String

    init {
        val (all, perThread) = recoverMethods(testClass)
        recoverAllMethod = all
        recoverPerThreadMethod = perThread
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        this.name = name!!
        shouldTransform = name == Type.getInternalName(testClass) ||
            recoverAllMethod !== null && name == Type.getInternalName(recoverAllMethod.declaringClass) ||
            recoverPerThreadMethod !== null && name == Type.getInternalName(recoverPerThreadMethod.declaringClass)
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (!shouldTransform) return mv
        return DurableRecoverOperationTransformer(this, GeneratorAdapter(mv, access, name, descriptor))
    }
}

internal class DurableRecoverAllGenerator(cv: ClassVisitor, _class: Class<*>) : ClassVisitor(ASM_API, cv) {
    private val recoverAllMethod = recoverMethods(_class)[0]

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        if (recoverAllMethod !== null && name == Type.getInternalName(recoverAllMethod.declaringClass))
            generateSynchronizedRecoverAll()
    }

    private fun generateSynchronizedRecoverAll(): Unit = GeneratorAdapter(
        super.visitMethod(
            RECOVER_ALL_GENERATED_ACCESS,
            RECOVER_ALL_GENERATED_NAME,
            RECOVER_DESCRIPTOR,
            null,
            emptyArray()
        ),
        RECOVER_ALL_GENERATED_ACCESS, RECOVER_ALL_GENERATED_NAME, RECOVER_DESCRIPTOR
    ).run {
        visitCode()
        generateRecoverCode(Type.getInternalName(recoverAllMethod!!.declaringClass), recoverAllMethod.name)
        visitInsn(Opcodes.RETURN)
        visitMaxs(1, 0)
        visitEnd()
    }
}

private fun GeneratorAdapter.generateRecoverCode(className: String, recoverMethod: String) {
    val endLabel = newLabel()
    invokeStatic(NVM_STATE_HOLDER_TYPE, IS_CRASHED_METHOD)
    visitJumpInsn(Opcodes.IFEQ, endLabel)
    loadThis()
    visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, recoverMethod, RECOVER_DESCRIPTOR, false)
    invokeStatic(NVM_STATE_HOLDER_TYPE, RESET_ALL_CRASHED_METHOD)
    visitLabel(endLabel)
}

/** Adds recover call to methods annotated with [Operation]. */
private class DurableRecoverOperationTransformer(
    private val cv: DurableOperationRecoverTransformer,
    private val adapter: GeneratorAdapter
) : MethodVisitor(ASM_API, adapter) {
    private var isOperation = false

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        if (descriptor == OPERATION_TYPE.descriptor) isOperation = true
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitCode() {
        super.visitCode()
        if (!isOperation) return

        val recoverAll = cv.recoverAllMethod
        val recoverPerThread = cv.recoverPerThreadMethod
        if (recoverAll !== null) {
            adapter.generateRecoverCode(cv.name, RECOVER_ALL_GENERATED_NAME)
        } else if (recoverPerThread !== null) {
            adapter.generateRecoverCode(cv.name, recoverPerThread.name)
        }
    }
}

private fun recoverMethods(clazz: Class<*>) = listOf(DurableRecoverAll::class, DurableRecoverPerThread::class)
    .map { a -> clazz.methods.singleOrNull { m -> m.annotations.any { it.annotationClass == a } } }
    .onEach {
        check(it == null || Type.getMethodDescriptor(it) == RECOVER_DESCRIPTOR) {
            """A recover method must have no arguments and return nothing.
            But method ${it?.name} has signature ${Type.getMethodDescriptor(it)}""".trimIndent()
        }
    }
