/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation.transformers.native_calls

import org.jetbrains.kotlinx.lincheck.transformation.ASM_API
import org.jetbrains.kotlinx.lincheck.transformation.invokeIfInTestingCode
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.GeneratorAdapter

/**
 * [FakeDeterministicTimeTransformer] tracks invocations of [System.nanoTime] and [System.currentTimeMillis] methods,
 * and replaces them with stubs to prevent non-determinism.
 */
internal class FakeDeterministicTimeTransformer(adapter: GeneratorAdapter) :
    AbstractDeterministicTimeMethodTransformer(adapter) {
    override fun GeneratorAdapter.generateInstrumentedCode(
        opcode: Int, owner: String, name: String, desc: String, itf: Boolean
    ) {
        push(1337L) // any constant value
    }
}

/**
 * [TrueDeterministicTimeTransformer] tracks invocations of [System.nanoTime] and [System.currentTimeMillis] methods,
 * and ensures deterministic behaviour by recording the results of the first invocations and replaying them during the subsequent calls.
 */
internal class TrueDeterministicTimeTransformer(
    adapter: GeneratorAdapter
) : AbstractDeterministicTimeMethodTransformer(adapter) {
    override fun GeneratorAdapter.generateInstrumentedCode(
        opcode: Int, owner: String, name: String, desc: String, itf: Boolean
    ) {
        invoke(SimpleDeterministicCall(opcode, owner, name, desc, itf))
    }
}

internal abstract class AbstractDeterministicTimeMethodTransformer(
    val adapter: GeneratorAdapter
) : MethodVisitor(ASM_API, adapter) {
    protected abstract fun GeneratorAdapter.generateInstrumentedCode(
        opcode: Int, owner: String, name: String, desc: String, itf: Boolean
    )

    final override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
        adapter.run {
            if (owner == "java/lang/System" && (name == "nanoTime" || name == "currentTimeMillis")) {
                invokeIfInTestingCode(
                    original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                    code = { generateInstrumentedCode(opcode, owner, name, desc, itf) }
                )
            } else {
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
        }
}