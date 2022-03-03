/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.nvm

import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.ASM_API
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.objectweb.asm.*
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method

/** Generate bytecode for NRL recovery. */
internal class RecoverabilityTransformer(cv: ClassVisitor) : ClassVisitor(ASM_API, cv) {
    private lateinit var name: String

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
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (name == "<init>") return mv
        return RecoverableMethodTransformer(GeneratorAdapter(mv, access, name, descriptor), descriptor, this.name)
    }
}

private val CRASH_NAME = Type.getInternalName(CrashError::class.java)

/**
 * Modifies body of method if it is marked with [Recoverable] annotation.
 * Generates a call to before method(if present), wraps method body with try-catch
 * and calls recover method in case of a crash.
 */
private class RecoverableMethodTransformer(
    private val adapter: GeneratorAdapter,
    private val descriptor: String?,
    className: String
) : MethodVisitor(ASM_API, adapter) {
    private var shouldTransform = false
    private var beforeName: String? = null
    private var recoverName: String? = null

    private val classType = Type.getType("L$className;")

    private val tryLabel = Label()
    private val catchLabel = Label()

    /** Check whether method has [Recoverable] annotation. */
    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        val av = super.visitAnnotation(descriptor, visible)
        if (descriptor != Type.getDescriptor(Recoverable::class.java)) return av
        shouldTransform = true
        return object : AnnotationVisitor(ASM_API, av) {
            override fun visit(name: String?, value: Any?) {
                super.visit(name, value)
                if (name == "recoverMethod") {
                    recoverName = (value as String).ifEmpty { null }
                } else if (name == "beforeMethod") {
                    beforeName = (value as String).ifEmpty { null }
                }
            }
        }
    }

    /** Call before if name is non-empty and wrap method body with try-catch. */
    override fun visitCode() = adapter.run {
        visitCode()
        if (!shouldTransform) return
        visitTryCatchBlock(tryLabel, catchLabel, catchLabel, CRASH_NAME)

        beforeName?.let {
            val beforeDescriptor = Type.getMethodDescriptor(Type.VOID_TYPE, *Type.getType(descriptor).argumentTypes)
            callUntilSuccess(it, beforeDescriptor)
        }

        visitLabel(tryLabel)
    }

    /** Call recover method in case of crash. */
    override fun visitMaxs(maxStack: Int, maxLocals: Int) = adapter.run {
        if (shouldTransform) {
            // crash, ignore exception
            visitLabel(catchLabel)
            pop()
            // call recover, it may be a
            callUntilSuccess(recoverName ?: name, descriptor)
                ?.let { result -> loadLocal(result) }
            returnValue()
        }
        visitMaxs(maxStack, maxLocals)
    }

    /**
     * Call [name] method with signature [descriptor] until it completes successfully.
     * @return index of local variable where result is stored or null in case of void return type
     */
    private fun callUntilSuccess(name: String, descriptor: String?): Int? = adapter.run {
        val (tryLabel, catchLabel, endLabel) = List(3) { Label() }
        visitTryCatchBlock(tryLabel, catchLabel, catchLabel, CRASH_NAME)

        val returnType = Type.getReturnType(descriptor)
        val result = if (returnType == Type.VOID_TYPE) null else newLocal(returnType).also {
            // init result
            pushDefaultValue(returnType)
            storeLocal(it)
        }

        visitLabel(tryLabel)

        // invoke `name` method
        loadThis()
        loadArgs()
        invokeVirtual(classType, Method(name, descriptor))

        // success, store result, finish
        result?.let { storeLocal(it) }
        goTo(endLabel)

        // fail, ignore exception, try again
        visitLabel(catchLabel)
        pop()
        goTo(tryLabel)

        visitLabel(endLabel)
        return result
    }

    /** Push a default value of type [type] on stack. */
    private fun pushDefaultValue(type: Type) = adapter.run {
        when (type.sort) {
            Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.INT -> push(0)
            Type.LONG -> push(0L)
            Type.DOUBLE -> push(0.0)
            Type.FLOAT -> push(0.0f)
            Type.ARRAY, Type.METHOD, Type.OBJECT -> visitInsn(Opcodes.ACONST_NULL)
        }
    }
}
