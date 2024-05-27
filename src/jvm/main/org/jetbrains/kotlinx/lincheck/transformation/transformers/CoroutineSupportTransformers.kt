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
import sun.nio.ch.lincheck.*

/**
 * [CoroutineCancellabilitySupportTransformer] tracks suspension points and saves the corresponding
 * cancellable continuation by injecting [Injections.storeCancellableContinuation] method call.
 */
internal class CoroutineCancellabilitySupportTransformer(
    mv: MethodVisitor,
    access: Int,
    methodName: String?,
    descriptor: String?
) : AdviceAdapter(ASM_API, mv, access, methodName, descriptor) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
        if (isCancellableContinuationGetResultMethod(owner, name)) {
            dup()
            invokeStatic(Injections::storeCancellableContinuation)
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf)
    }

    private fun isCancellableContinuationGetResultMethod(className: String, methodName: String): Boolean =
        (methodName == "getResult") && (
            className == "kotlinx/coroutines/CancellableContinuation" ||
            className == "kotlinx/coroutines/CancellableContinuationImpl"
        )

}