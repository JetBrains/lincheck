/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.transformers

import org.objectweb.asm.MethodVisitor
import org.jetbrains.lincheck.jvm.agent.*
import org.objectweb.asm.Type.getType
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.*

/**
 * [CoroutineCancellabilitySupportTransformer] tracks suspension points and saves the corresponding
 * cancellable continuation by injecting [Injections.storeCancellableContinuation] method call.
 */
internal class CoroutineCancellabilitySupportTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        val isGetResult = (name == "getResult") && (
            owner == "kotlinx/coroutines/CancellableContinuation" ||
            owner == "kotlinx/coroutines/CancellableContinuationImpl"
        )
        if (isGetResult) {
            dup()
            invokeStatic(Injections::storeCancellableContinuation)
            coroutineCallingClasses += className.toCanonicalClassName()
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf)
    }
}

// Set storing canonical names of the classes that call internal coroutine functions;
// it is used to optimize class re-transformation in stress mode by remembering
// exactly what classes need to be re-transformed (only the coroutines calling classes)
internal val coroutineCallingClasses = HashSet<String>()

/**
 * [CoroutineDelaySupportTransformer] substitutes each invocation of [kotlinx.coroutines.delay] function with
 * zero delay time. For example, it will transform `delay(100)` to `delay(0)`.
 *
 * It is sufficient to only transform `delay(Long)` overload, because
 * the `delay(Duration)` overload is compiled to the invocation of the first one.
 */
internal class CoroutineDelaySupportTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (!isCoroutineDelay(owner, name, desc)) {
            super.visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }

        invokeIfInAnalyzedCode(
            original = {
                super.visitMethodInsn(opcode, owner, name, desc, itf)
            },
            instrumented = {
                // STACK [INVOKESTATIC]: delay, <cont>
                val contLocal = newLocal(getType("Lkotlin/coroutines/Continuation;"))
                storeLocal(contLocal)
                // STACK [INVOKESTATIC]: delay
                pop2()
                push(0L)
                loadLocal(contLocal)
                // STACK [INVOKESTATIC]: 0L, <cont>
                super.visitMethodInsn(opcode, owner, name, desc, itf)
            }
        )
    }

    private fun isCoroutineDelay(owner: String, methodName: String, desc: String): Boolean = (
        owner == "kotlinx/coroutines/DelayKt" &&
        methodName == "delay" &&
        desc == "(JLkotlin/coroutines/Continuation;)Ljava/lang/Object;"
    )
}