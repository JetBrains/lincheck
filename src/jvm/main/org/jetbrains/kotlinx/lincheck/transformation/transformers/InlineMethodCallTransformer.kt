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

import org.jetbrains.kotlinx.lincheck.transformation.*
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import sun.nio.ch.lincheck.*

/**
 * [InlineMethodCallTransformer] tracks method calls,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class InlineMethodCallTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
    val locals: MethodVariables
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
    val inlineStack = ArrayList<LocalVariableInfo>()

    override fun visitLabel(label: Label?)  = adapter.run {
        if (label == null || !locals.hasInlines) {
            super.visitLabel(label)
            return
        }
        // TODO Find a way to sort multiple marker variables with same start by end label
        var lvar = locals.inlineStartMarkers[label]?.first()
        if (lvar != null) {
            // Start a new inline call: We cannot start a true call as we don't have a lot of necessary
            // information, such as method descriptor, variables' types, etc.
            inlineStack.add(lvar)
            visitLabel(label)
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    processInlineMethodCall(lvar!!.inlineMethodName, label)
                }
            )
            return
        }
        // TODO Find a way to sort multiple marker variables with same start by end label
        lvar = inlineStack.lastOrNull()
        if (lvar?.labelIndexRange?.second == label) {
            // Check that the stack is nor broken against all possible labels in `locals`?
            inlineStack.removeLast()
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    processInlineMethodCallReturn(lvar.inlineMethodName, lvar.labelIndexRange.second)
                }
            )
            visitLabel(label)
            return
        }

        super.visitLabel(label)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun processInlineMethodCall(inlineMethodName: String, startLabel: Label) = adapter.run {
        // Create a fake method descriptor
        val methodId = getPseudoMethodId(startLabel, inlineMethodName)
        push(inlineMethodName)
        push(methodId)
        loadNewCodeLocationId()
        invokeStatic(Injections::onInlineMethodCall)
        invokeBeforeEventIfPluginEnabled("inline method call $inlineMethodName in $methodName", setMethodEventId = true)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun processInlineMethodCallReturn(inlineMethodName: String, startLabel: Label) = adapter.run {
        // Create a fake method descriptor
        val methodId = getPseudoMethodId(startLabel, inlineMethodName)
        push(inlineMethodName)
        push(methodId)
        invokeStatic(Injections::onInlineMethodCallReturn)
    }

    private fun getPseudoMethodId(startLabel: Label, inlineMethodName: String): Int =
        MethodIds.getMethodId("$className\$$methodName\$$startLabel\$inlineCall", inlineMethodName, "()V")
}