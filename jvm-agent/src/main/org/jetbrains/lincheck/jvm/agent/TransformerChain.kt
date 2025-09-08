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
    initialMethodVisitor: MethodVisitor,
) {
    private val _methodVisitors = mutableListOf(initialMethodVisitor)
    val methodVisitors: List<MethodVisitor> get() = _methodVisitors

    inline fun <reified T : MethodVisitor> addTransformer(crossinline factory: (GeneratorAdapter, MethodVisitor) -> T) {
        if (config.shouldApplyVisitor(T::class.java)) {
            val visitor = factory(adapter, methodVisitors.last())
            _methodVisitors += visitor.getVisitors(methodVisitors.last())
        }
    }
}

private fun MethodVisitor.getVisitors(initialVisitor: MethodVisitor): List<MethodVisitor> {
    val visitors = mutableListOf<MethodVisitor>()
    var currentVisitor = this
    while (currentVisitor !== initialVisitor) {
        visitors.add(currentVisitor)
        currentVisitor = currentVisitor.delegate ?: break
    }
    return visitors.reversed()
}