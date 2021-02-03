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
import org.jetbrains.kotlinx.lincheck.annotations.CrashFree
import org.objectweb.asm.*
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method

private val CRASH_FREE_TYPE = Type.getDescriptor(CrashFree::class.java)

class CrashTransformer(cv: ClassVisitor, testClass: Class<*>) : ClassVisitor(ASM_API, cv) {
    private var shouldTransform = true
    private val testClassName = Type.getInternalName(testClass)

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        if (name == testClassName) {
            shouldTransform = false
        }
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        if (descriptor == CRASH_FREE_TYPE) {
            shouldTransform = false
        }
        return super.visitAnnotation(descriptor, visible)
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
        if (name == "<clinit>") return mv
        if (name == "<init>") return CrashConstructorTransformer(mv, access, name, descriptor)
        return CrashMethodTransformer(mv, access, name, descriptor)
    }
}

private open class CrashBaseMethodTransformer(
    mv: MethodVisitor,
    access: Int,
    name: String?,
    descriptor: String?
) : GeneratorAdapter(ASM_API, mv, access, name, descriptor) {

    private val crashOwnerType = Type.getType(Crash::class.java)
    private var shouldTransform = true

    protected open fun callCrash() {
        if (!shouldTransform) return
        super.invokeStatic(crashOwnerType, Method("possiblyCrash", "()V"))
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        if (descriptor == CRASH_FREE_TYPE) {
            shouldTransform = false
        }
        return super.visitAnnotation(descriptor, visible)
    }
}

private class CrashMethodTransformer(
    mv: MethodVisitor,
    access: Int,
    name: String?,
    descriptor: String?
) : CrashBaseMethodTransformer(mv, access, name, descriptor) {
    override fun visitCode() {
        super.visitCode()
        callCrash()
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        callCrash()
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}

private class CrashConstructorTransformer(
    mv: MethodVisitor,
    access: Int,
    name: String?,
    descriptor: String?
) : CrashBaseMethodTransformer(mv, access, name, descriptor) {
    private var superConstructorCalled = false

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        if (!superConstructorCalled && opcode == Opcodes.INVOKESPECIAL) {
            superConstructorCalled = true
        }
    }

    override fun callCrash() {
        if (superConstructorCalled) {
            super.callCrash()
        }
    }
}
