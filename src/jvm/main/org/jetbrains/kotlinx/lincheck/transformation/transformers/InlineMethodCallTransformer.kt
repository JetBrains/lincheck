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
import org.jetbrains.lincheck.trace.TRACE_CONTEXT
import org.objectweb.asm.Label
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
    desc: String,
    adapter: GeneratorAdapter,
    val locals: MethodVariables,
    val localsTracker: LocalVariablesAccessTransformer?
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
    private companion object {
        val objectType = getObjectType("java/lang/Object").className
        val contType = getObjectType("kotlin/coroutines/Continuation").className
    }

    private val methodType = getMethodType(desc)
    private val looksLikeSuspendMethod =
        methodType.returnType.className == objectType &&
        methodType.argumentTypes.lastOrNull()?.className == contType &&
        (
            locals.hasVarByName("\$completion") ||
            locals.hasVarByName("\$continuation") ||
            locals.hasVarByName("\$result")
        )

    private val inlineStack = ArrayList<LocalVariableInfo>()

    override fun visitLabel(label: Label) = adapter.run {
        if (!locals.hasInlines || looksLikeSuspendMethod) {
            super.visitLabel(label)
            return
        }

        // TODO Find a way to sort multiple marker variables with same start by end label
        var lvar = locals.inlinesStartAt(label).firstOrNull()
        // Sometimes Kotlin compiler generate "inline marker" inside inline function itself, which
        // covers the whole function. Skip this, there is no "inlined call"
        if (lvar != null && lvar.inlineMethodName != methodName && isSupportedInline(lvar)) {
            // Start a new inline call: We cannot start a true call as we don't have a lot of necessary
            // information, such as method descriptor, variables' types, etc.
            inlineStack.add(lvar)
            val suffix = "\$iv".repeat(inlineStack.size)
            val inlineName = lvar.inlineMethodName!!

            //TODO Find out what the exact problem is here
            if (inlineName == "recoverStackTrace") {
                super.visitLabel(label)
                return
            }

            // If an extension function was inlined, `this_$iv` will point to class where extension
            // function was defined and `$this$<func-name>$iv` will show to virtual `this`, with
            // which function should really work.
            // Prefer the second variant if possible.
            val this_ =
                locals.activeVariables.firstOrNull { it.name == "\$this$$inlineName$suffix" } ?:
                locals.activeVariables.firstOrNull { it.name == "this_$suffix" }
            val clazz = this_?.type
            val className = if (clazz?.sort == OBJECT) clazz.className else ""
            val thisLocal = if (clazz?.sort == OBJECT) this_.index else null

            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    processInlineMethodCall(className, inlineName, clazz, thisLocal, label)
                }
            )
            visitLabel(label)
            return
        }
        // TODO Find a way to sort multiple marker variables with same start by end label
        lvar = inlineStack.lastOrNull()
        if (lvar?.labelIndexRange?.second == label) {
            // Check that the stack is not broken against all possible labels in `locals`?
            inlineStack.removeLast()
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    processInlineMethodCallReturn(lvar.inlineMethodName!!, lvar.labelIndexRange.second)
                }
            )
            visitLabel(label)
            return
        }

        super.visitLabel(label)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        super.visitMaxs(maxStack, maxLocals)
        if (inlineStack.isNotEmpty()) {
            System.err.println("Inline methods calls are not balanced at $className.$methodName:")
            inlineStack.reversed().forEach {
                System.err.println("  ${it.name} (slot ${it.index}) called at label ${it.labelIndexRange.first}")
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun processInlineMethodCall(className: String, inlineMethodName: String, ownerType: org.objectweb.asm.Type?, owner: Int?, startLabel: Label) = adapter.run {
        // Create a fake method descriptor
        val methodId = getPseudoMethodId(className, startLabel, inlineMethodName)
        push(methodId)
        loadNewCodeLocationId()
        if (owner == null) {
            pushNull()
        }
        else {
            val asmType = getLocalType(owner)
            runWithoutLocalVariablesTracking {
                if (asmType == null) {
                    loadLocal(owner, ownerType)
                } else if (asmType.sort == ownerType?.sort) {
                    loadLocal(owner)
                } else {
                    // Sometimes ASM freaks out when a slot has completely different types in different frames.
                    // Like, two variables of types Int and Object share a slot, and ASM thinks about it as about Int,
                    // and we try to load it as an Object.
                    pushNull()
                }
            }
        }
        invokeStatic(Injections::onInlineMethodCall)
        invokeBeforeEventIfPluginEnabled("inline method call $inlineMethodName in $methodName", setMethodEventId = true)
    }

    private fun runWithoutLocalVariablesTracking(block: GeneratorAdapter.() -> Unit) {
        if (localsTracker == null) {
            adapter.block()
            return
        }
        localsTracker.runWithoutLocalVariablesTracking {
            adapter.block()
        }
    }

    private fun processInlineMethodCallReturn(inlineMethodName: String, startLabel: Label) = adapter.run {
        // Create a fake method descriptor
        val methodId = getPseudoMethodId(null, startLabel, inlineMethodName)
        push(methodId)
        invokeStatic(Injections::onInlineMethodCallReturn)
    }

    private fun getPseudoMethodId(possibleClassName: String?, startLabel: Label, inlineMethodName: String): Int =
        TRACE_CONTEXT.getOrCreateMethodId(possibleClassName ?: "$className\$$methodName\$$startLabel\$inlineCall", inlineMethodName, "()V")

    // Don't support atomicfu for now, it is messed with stack
    // Maybe we will need to expand it later
    // Check inlined method name by marker variable name
    private fun isSupportedInline(lvar: LocalVariableInfo) = !lvar.name.endsWith("\$atomicfu")
}