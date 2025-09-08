/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.GeneratorAdapter

internal class TransformerChain(
    val config: TransformationConfiguration,
    val adapter: GeneratorAdapter,
    val initialMethodVisitor: MethodVisitor,
) {
    var methodVisitor: MethodVisitor = initialMethodVisitor
        private set

    val methodVisitors: List<MethodVisitor> get() {
        val visitors = mutableListOf<MethodVisitor>()
        var currentVisitor = methodVisitor
        while (currentVisitor !== initialMethodVisitor) {
            visitors += currentVisitor
            currentVisitor = currentVisitor.delegate ?: break
        }
        return visitors.reversed()
    }

    inline fun <reified T : MethodVisitor> addTransformer(crossinline factory: (GeneratorAdapter, MethodVisitor) -> T) {
        if (config.shouldApplyVisitor(T::class.java)) {
            methodVisitor = factory(adapter, methodVisitor)
        }
    }
}