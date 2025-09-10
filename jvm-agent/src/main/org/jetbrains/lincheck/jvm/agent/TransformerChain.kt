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
import org.objectweb.asm.commons.AnalyzerAdapter
import org.objectweb.asm.commons.GeneratorAdapter

internal class TransformerChain(
    val config: TransformationConfiguration,
    val adapter: GeneratorAdapter,
    initialMethodVisitor: MethodVisitor,
) {
    private val _methodVisitors = mutableListOf(initialMethodVisitor)
    val methodVisitors: List<MethodVisitor> get() = _methodVisitors

    inline fun <reified T : MethodVisitor> addTransformer(crossinline factory: (GeneratorAdapter, MethodVisitor) -> T): T? {
        if (config.shouldApplyVisitor(T::class.java)) {
            val newVisitor = factory(adapter, methodVisitors.last())
            _methodVisitors += newVisitor.getVisitors(methodVisitors.last())
            return newVisitor
        }
        return null
    }

    fun addAnalyzerAdapter(access: Int, className: String, methodName: String, desc: String) {
        val requiresTypeAnalyzer = methodVisitors.any {
            it is LincheckMethodVisitor && it.requiresTypeAnalyzer
        }
        if (requiresTypeAnalyzer) {
            val analyzer = addTransformer { _, mv ->
                AnalyzerAdapter(className, access, methodName, desc, mv)
            }
            for (visitor in methodVisitors) {
                if (visitor is LincheckMethodVisitor && visitor.requiresTypeAnalyzer) {
                    visitor.typeAnalyzer = analyzer
                }
            }
        }
    }

    fun addOwnerNameAnalyzerAdapter(access: Int, className: String, methodName: String, desc: String, methodInfo: MethodInformation) {
        val requiresOwnerNameAnalyzer = methodVisitors.any {
            it is LincheckMethodVisitor && it.requiresOwnerNameAnalyzer
        }
        if (requiresOwnerNameAnalyzer) {
            val analyzer = addTransformer { _, mv ->
                OwnerNameAnalyzerAdapter(className, access, methodName, desc, mv, methodInfo.locals)
            }
            for (visitor in methodVisitors) {
                if (visitor is LincheckMethodVisitor && visitor.requiresOwnerNameAnalyzer) {
                    visitor.ownerNameAnalyzer = analyzer
                }
            }
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