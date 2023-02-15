/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.commons.Method
import kotlin.reflect.jvm.javaMethod

internal class CancellabilitySupportClassTransformer(cv: ClassVisitor) : ClassVisitor(ASM_API, cv) {
    override fun visitMethod(access: Int, methodName: String?, desc: String?, signature: String?, exceptions: Array<String>?): MethodVisitor {
        val mv = super.visitMethod(access, methodName, desc, signature, exceptions)
        return CancellabilitySupportMethodTransformer(access, methodName, desc, mv)
    }
}

private class CancellabilitySupportMethodTransformer(access: Int, methodName: String?, desc: String?, mv: MethodVisitor)
    : AdviceAdapter(ASM_API, mv, access, methodName, desc)
{
    override fun visitMethodInsn(opcodeAndSource: Int, className: String?, methodName: String?, descriptor: String?, isInterface: Boolean) {
        val isGetResult = ("kotlinx/coroutines/CancellableContinuation" == className || "kotlinx/coroutines/CancellableContinuationImpl" == className)
                          && "getResult" == methodName
        if (isGetResult) {
            this.dup()
            this.invokeStatic(storeCancellableContOwnerType, storeCancellableContMethod)
        }
        super.visitMethodInsn(opcodeAndSource, className, methodName, descriptor, isInterface)
    }
}

private val storeCancellableContMethod = Method.getMethod(::storeCancellableContinuation.javaMethod)
private val storeCancellableContOwnerType = Type.getType(::storeCancellableContinuation.javaMethod!!.declaringClass)