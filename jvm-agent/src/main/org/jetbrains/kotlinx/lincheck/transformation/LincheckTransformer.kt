/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.GeneratorAdapter

internal abstract class LincheckTransformer {
    abstract fun apply(adapter: GeneratorAdapter, methodVisitor: MethodVisitor): MethodVisitor
}

internal class LincheckTransformerChain(
    private val config: TransformationConfiguration,
) : LincheckTransformer() {
    private val _transformers = mutableListOf<LincheckTransformer>()
    val transformers: List<LincheckTransformer> get() = _transformers

    private val _methodVisitors = mutableListOf<MethodVisitor>()
    val methodVisitors: List<MethodVisitor> get() = _methodVisitors

    fun addTransformer(transformer: LincheckTransformer) {
        _transformers.add(transformer)
    }

    override fun apply(adapter: GeneratorAdapter, methodVisitor: MethodVisitor): MethodVisitor {
        var mv = methodVisitor
        for (transformer in _transformers) {
            if (config.shouldApplyTransformer(transformer)) {
                mv = transformer.apply(adapter, mv)
                _methodVisitors.add(mv)
            }
        }
        return mv
    }
}