/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation.transformers

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.AdviceAdapter
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.kotlinx.lincheck.canonicalClassName
import sun.nio.ch.lincheck.*

/**
 * [CoroutineCancellabilitySupportTransformer] tracks suspension points and saves the corresponding
 * cancellable continuation by injecting [Injections.storeCancellableContinuation] method call.
 */
internal class CoroutineCancellabilitySupportTransformer(
    mv: MethodVisitor,
    access: Int,
    val className: String?,
    methodName: String?,
    desc: String?
) : AdviceAdapter(ASM_API, mv, access, methodName, desc) {

    override fun visitMethodInsn(
        opcodeAndSource: Int,
        className: String?,
        methodName: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        val isGetResult = (methodName == "getResult") && (
            className == "kotlinx/coroutines/CancellableContinuation" ||
            className == "kotlinx/coroutines/CancellableContinuationImpl"
        )
        if (isGetResult) {
            this.className?.canonicalClassName?.let {
                coroutineCallingClasses += it.canonicalClassName
            }
            dup()
            invokeStatic(Injections::storeCancellableContinuation)
        }
        super.visitMethodInsn(opcodeAndSource, className, methodName, descriptor, isInterface)
    }

}