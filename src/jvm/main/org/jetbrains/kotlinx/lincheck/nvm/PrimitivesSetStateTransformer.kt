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
import org.jetbrains.kotlinx.lincheck.nvm.api.NonVolatileInt
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

private val NON_VOLATILE_METHOD = Unit.let {
    val ref: (Int) -> NonVolatileInt = ::nonVolatile
    (ref as KFunction<*>).javaMethod!!
}
private val NVM_STATE_TYPE = Type.getType(NVMState::class.java)
private val NVM_STATE_HOLDER = Type.getType(NVMStateHolder::class.java)
private val PRIMITIVES_TYPE = Type.getType(NON_VOLATILE_METHOD.declaringClass)

internal class PrimitivesSetStateTransformer(cv: ClassVisitor) : ClassVisitor(ASM_API, cv) {
    override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        val adapter = GeneratorAdapter(mv, access, name, descriptor)
        return object : MethodVisitor(ASM_API, adapter) {
            override fun visitMethodInsn(opcode: Int, owner: String?, name: String?, descriptor: String?, isInterface: Boolean) {
                if (descriptor !== null && owner == PRIMITIVES_TYPE.internalName && name == NON_VOLATILE_METHOD.name) {
                    val (params, returnType) = descriptor.split(')')
                    val methodWithStateDescriptor = params + NVM_STATE_TYPE.descriptor + ')' + returnType

                    adapter.getStatic(NVM_STATE_HOLDER, "state", NVM_STATE_TYPE)

                    super.visitMethodInsn(opcode, owner, name, methodWithStateDescriptor, isInterface)
                    return
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
        }
    }
}
