/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.*
import kotlin.reflect.jvm.*

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