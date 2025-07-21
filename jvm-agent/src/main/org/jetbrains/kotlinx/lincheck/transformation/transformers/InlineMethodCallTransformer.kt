/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation.transformers

import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.lincheck.trace.TRACE_CONTEXT
import org.jetbrains.lincheck.util.Logger
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.MethodVisitor
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
    methodVisitor: MethodVisitor,
    val locals: MethodVariables,
) : LincheckBaseMethodVisitor(fileName, className, methodName, adapter, methodVisitor) {
    private companion object {
        val objectType: String = getObjectType("java/lang/Object").className
        val contType: String = getObjectType("kotlin/coroutines/Continuation").className
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

    private val inlineStack = ArrayList<Pair<String?, LocalVariableInfo>>()

    override fun visitLabel(label: Label) = adapter.run {
        if (!locals.hasInlines || looksLikeSuspendMethod) {
            super.visitLabel(label)
            return
        }

        // TODO Find a way to sort multiple marker variables with same start by end label
        val lvar = locals.inlinesStartAt(label).firstOrNull()
        // Sometimes Kotlin compiler generate "inline marker" inside inline function itself, which
        // covers the whole function. Skip this, there is no "inlined call"
        if (lvar != null && lvar.inlineMethodName != methodName && isSupportedInline(lvar)) {
            val suffix = "\$iv".repeat(inlineStack.size + 1)
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
            val className = if (clazz?.sort == OBJECT) clazz.className else null
            val thisLocal = if (clazz?.sort == OBJECT) this_.index else null

            // Start a new inline call: We cannot start a true call as we don't have a lot of necessary
            // information, such as method descriptor, variables' types, etc.
            inlineStack.add(className to lvar)

            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    System.err.println(">>> ${this@InlineMethodCallTransformer.className}.$methodName: LABEL $label STARTS")
                    System.err.println("=== ADD ${lvar.name} (${lvar.labelIndexRange.first} .. ${lvar.labelIndexRange.second})")
                    processInlineMethodCall(className, inlineName, clazz, thisLocal, label)
                    System.err.println("<<< ${this@InlineMethodCallTransformer.className}.$methodName: LABEL $label ENDS")
                }
            )
            super.visitLabel(label)
            return
        }
        // TODO Find a way to sort multiple marker variables with same start by end label
        val topOfStack = inlineStack.lastOrNull()
        if (topOfStack?.second?.labelIndexRange?.second == label) {
            val (className, lvar) = topOfStack
            // Check that the stack is not broken against all possible labels in `locals`?
            inlineStack.removeLast()
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    System.err.println(">>> $className.$methodName: LABEL $label STARTS")
                    System.err.println("=== REMOVE ${lvar.name} (${lvar.labelIndexRange.first} .. ${lvar.labelIndexRange.second})")
                    push("~~~ Exit by Label End ${exits++}")
                    invokeStatic(Injections::debugPrint)
                    processInlineMethodCallReturn(className, lvar.inlineMethodName!!, lvar.labelIndexRange.first)
                    System.err.println("<<< $className.$methodName: LABEL $label ENDS")
                }
            )
            super.visitLabel(label)
            return
        }

        super.visitLabel(label)
    }

    var exits = 0

    override fun visitJumpInsn(opcode: Int, label: Label) = adapter.run {
        // Maybe we jump out of the inline stack?
        val topOfStack = inlineStack.lastOrNull()
        // we must not pop the static analysis stack here, as an inline method is not ended here!
        if (topOfStack != null && labelSorter.compare(topOfStack.second.labelIndexRange.second, label) <= 0) {
            when (opcode) {
                GOTO -> invokeIfInAnalyzedCode(
                    original = {},
                    instrumented = {
                        exitFromInlineMethods { lvar -> labelSorter.compare(lvar.labelIndexRange.second, label) <= 0 }
                    }
                )
                IFEQ,
                IFNE,
                IFLT,
                IFGE,
                IFGT,
                IFLE,
                IFNULL,
                IFNONNULL -> {
                    // Duplicate one top category-1 value
                    dup()
                    invokeIfTrueAndInAnalyzedCode(opcode) {
                        exitFromInlineMethods { lvar -> labelSorter.compare(lvar.labelIndexRange.second, label) <= 0 }
                    }
                }
                IF_ICMPEQ,
                IF_ICMPNE,
                IF_ICMPLT,
                IF_ICMPGE,
                IF_ICMPGT,
                IF_ICMPLE,
                IF_ACMPEQ,
                IF_ACMPNE -> {
                    // Duplicate two top category-1 values
                    dup2()
                    invokeIfTrueAndInAnalyzedCode(opcode) {
                        exitFromInlineMethods { lvar -> labelSorter.compare(lvar.labelIndexRange.second, label) <= 0 }
                    }
                }
                JSR -> Unit
                else -> error("Unknown conditional opcode $opcode")
            }
        }
        visitJumpInsn(opcode, label)
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        when (opcode) {
            ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN, ATHROW -> {
                // we must not pop the static analysis stack here, as an inline method is not ended here!
                if (inlineStack.isNotEmpty()) {
                    invokeIfInAnalyzedCode(
                        original = {},
                        instrumented = {
                            exitFromInlineMethods { false }
                        }
                    )
                }
            }
        }
        visitInsn(opcode)
    }

    private fun exitFromInlineMethods(stopHere: (LocalVariableInfo) -> Boolean) {
        for (topOfStack in inlineStack.reversed()) {
            val (className, lvar) = topOfStack
            // This inline call covers this jump, stop "exiting"
            if (stopHere(topOfStack.second)) {
                break
            }
            processInlineMethodCallReturn(className, lvar.inlineMethodName!!, lvar.labelIndexRange.first)
        }
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        if (inlineStack.isNotEmpty()) {
            Logger.warn {
                "Inline methods calls are not balanced at $className.$methodName:\n" +
                inlineStack.reversed().joinToString(separator = "\n") {
                    val (className, lvar) = it
                    val cn = className ?: "<unknown class>"
                    "  $cn ${lvar.name} (slot ${lvar.index}) called at label ${lvar.labelIndexRange.first}"
                }
            }
        }
        super.visitMaxs(maxStack, maxLocals)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun processInlineMethodCall(className: String?, inlineMethodName: String, ownerType: Type?, owner: Int?, startLabel: Label) = adapter.run {
        // Create a fake method descriptor
        val methodId = getPseudoMethodId(className, startLabel, inlineMethodName)
        System.err.println("@@@ $className / $startLabel / $inlineMethodName : $methodId")
        push(methodId)
        loadNewCodeLocationId()
        if (owner == null) {
            pushNull()
        }
        else {
            val asmType = getLocalType(owner)
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
        invokeStatic(Injections::onInlineMethodCall)
        invokeBeforeEventIfPluginEnabled("inline method call $inlineMethodName in $methodName")
    }

    private fun processInlineMethodCallReturn(inlineMethodName: String, startLabel: Label) = adapter.run {
        // Create a fake method descriptor
        val methodId = getPseudoMethodId(className, startLabel, inlineMethodName)
        System.err.println("%%% $className / $startLabel / $inlineMethodName : $methodId")
        push(methodId)
        invokeStatic(Injections::onInlineMethodCallReturn)
    }

    private fun getPseudoMethodId(possibleClassName: String?, startLabel: Label, inlineMethodName: String): Int =
        TRACE_CONTEXT.getOrCreateMethodId(possibleClassName ?: "$className\$$methodName\$$startLabel\$inlineCall", inlineMethodName, "()V")

    // Don't support atomicfu for now, it is messed with stack
    // Maybe we will need to expand it later
    // Check inlined method name by marker variable name
    private fun isSupportedInline(lvar: LocalVariableInfo) = !lvar.name.endsWith("\$atomicfu")

    private fun GeneratorAdapter.invokeIfTrueAndInAnalyzedCode(
        ifOpcode: Int,
        block: GeneratorAdapter.() -> Unit,
    ) {
        val falseLabel = Label()
        // If opcode has needed arguments on the top of the stack
        visitJumpInsn(invertJumpOpcode(ifOpcode), falseLabel)
        invokeStatic(Injections::inAnalyzedCode)
        visitJumpInsn(IFEQ, falseLabel)
        block()
        visitLabel(falseLabel)
    }

    private fun invertJumpOpcode(opcode: Int): Int =
        when (opcode) {
            IFEQ -> IFNE
            IFNE -> IFEQ
            IFLT -> IFGE
            IFGE -> IFLT
            IFGT -> IFLE
            IFLE -> IFGT
            IF_ICMPEQ -> IF_ICMPNE
            IF_ICMPNE -> IF_ICMPEQ
            IF_ICMPLT -> IF_ICMPGE
            IF_ICMPGE -> IF_ICMPLT
            IF_ICMPGT -> IF_ICMPLE
            IF_ICMPLE -> IF_ICMPGT
            IF_ACMPEQ -> IF_ACMPNE
            IF_ACMPNE -> IF_ACMPEQ
            IFNULL -> IFNONNULL
            IFNONNULL -> IFNULL
            else -> error("Unknown conitional opcode $opcode")
        }
}
