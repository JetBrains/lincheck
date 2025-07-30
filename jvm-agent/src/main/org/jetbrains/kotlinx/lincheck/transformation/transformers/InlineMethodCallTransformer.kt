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
import org.jetbrains.kotlinx.lincheck.transformation.invokeIfInAnalyzedCode
import org.jetbrains.kotlinx.lincheck.transformation.invokeStatic
import org.jetbrains.lincheck.trace.TRACE_CONTEXT
import org.jetbrains.lincheck.util.Logger
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.EventTracker
import sun.nio.ch.lincheck.Injections

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
    val labelSorter: MethodLabels
) : LincheckBaseMethodVisitor(fileName, className, methodName, adapter, methodVisitor) {
    private data class InlineStackElement(
        val lvar: LocalVariableInfo,
        val methodId: Int,
        val tryEndsCatchBeginsLabel: Label,
        val inlineDepth: Int
    )

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

    // Sort local variables by their end labels (earlier label goes first)
    // and then by index if labels are the same (to have a stable result).
    private val localVarInlineStartComparator = Comparator<LocalVariableInfo>
    { a, b -> labelSorter.compare(a.endLabel, b.endLabel) }
        .thenComparing { it.index }

    private val inlineStack = mutableListOf<InlineStackElement>()
    private var currentInlineDepth = 0

    override fun visitLabel(label: Label) {
        if (!locals.hasInlines || looksLikeSuspendMethod) {
            super.visitLabel(label)
            return
        }

        // It is not clear, could one label be used to mark the end of one inline call and
        // the start of another one. Nothing wrong instrument exists first.
        while (inlineStack.isNotEmpty()) {
            val (lvar, methodId, tryEndsCatchBeginsLabel, savedInlineDepth) = inlineStack.last()
            val cmp = labelSorter.compare(lvar.endLabel, label)
            if (cmp > 0) break
            if (cmp < 0) {
                Logger.warn { "${className}.${methodName}: Local call to ${lvar.inlineMethodName} should be finished at label ${lvar.endLabel} but still alive at ${label}." }
            }
            emitInlinedMethodEpilogue(methodId, tryEndsCatchBeginsLabel)
            inlineStack.removeLast()
            currentInlineDepth = savedInlineDepth
        }

        // Visit the label itself
        super.visitLabel(label)

        // Can one start label mark the beginning of several inlined methods?
        // One end label can mark several returns for sure.
        // Support multiple starts to be sure.
        // Sort starts by end labels (it provides proper nesting) and then variable slots (it provides stable sort)
        for (lvar in locals.inlinesStartAt(label)
            .filter { it.inlineMethodName != methodName && isSupportedInline(it) }
            .sortedWith(localVarInlineStartComparator)
        ) {
            inlineStack.add(
                emitInlineMethodPrologue(lvar)
            )
        }
    }

    override fun visitJumpInsn(opcode: Int, label: Label) {
        // This is a jump inside the current inline call
        if (!topOfStackEndsBeforeOrAtLabel(label)) {
            super.visitJumpInsn(opcode, label)
            return
        }
        // we must not pop the static analysis stack here, as an inline method is not ended here!
        // So, only generate injection and don't alter the stack
        when (opcode) {
            GOTO -> adapter.invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    // Stop when we jump inside the inline method, comparison is strict because
                    // "normal" exit code will be generated BEFORE this label (as method epilogue)
                    exitFromInlineMethods { labelSorter.compare(it.endLabel, label) > 0 }
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
                adapter.dup()
                adapter.invokeIfTrueAndInAnalyzedCode(opcode) {
                    // Stop when we jump inside the inline method, comparison is strict because
                    // "normal" exit code will be generated BEFORE this label (as method epilogue)
                    exitFromInlineMethods { labelSorter.compare(it.endLabel, label) > 0 }
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
                adapter.dup2()
                adapter.invokeIfTrueAndInAnalyzedCode(opcode) {
                    // Stop when we jump inside the inline method, comparison is strict because
                    // "normal" exit code will be generated BEFORE this label (as method epilogue)
                    exitFromInlineMethods { labelSorter.compare(it.endLabel, label) > 0 }
                }
            }

            JSR -> Unit
            else -> error("Unknown conditional opcode $opcode")
        }
        super.visitJumpInsn(opcode, label)
    }

    override fun visitInsn(opcode: Int) {
        if (inlineStack.isEmpty()) {
            super.visitInsn(opcode)
            return
        }
        // we must not pop the static analysis stack here, as an inline method is not ended here!
        // So, only generate injection and don't alter the stack
        when (opcode) {
            // Don't process ATHROW, it will be processed in catch blocks
            ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                adapter.invokeIfInAnalyzedCode(
                    original = {},
                    instrumented = {
                        exitFromInlineMethods { false }
                    }
                )
            }
        }
        super.visitInsn(opcode)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        while (inlineStack.isNotEmpty()) {
            val (lvar, methodId, tryEndsCatchBeginsLabel) = inlineStack.removeLast()
            Logger.error {
                "Inline method call is not balanced at $className.$methodName: " +
                "${lvar.inlineMethodName} (slot ${lvar.index}, methodId $methodId) " +
                "called at label ${lvar.startLabel}"
            }
            emitInlinedMethodEpilogue(methodId, tryEndsCatchBeginsLabel)
        }
        super.visitMaxs(maxStack, maxLocals)
    }

    /**
     * Generate exits from inline methods till stopped by callback without any conditions.
     * It assumes that this call is protected by if statements outside.
     * Now inline methods cannot affect analysis sections, so it is optimal to call
     * callbacks for all of them in one batch (under one `if`) if possible.
     */
    private fun exitFromInlineMethods(stopHere: (LocalVariableInfo) -> Boolean) {
        for (topOfStack in inlineStack.asReversed()) {
            val (lvar, methodId, _) = topOfStack
            // Check, should we stop at this stack level
            if (stopHere(lvar)) break
            processInlineMethodReturn(methodId)
        }
    }

    private fun emitInlineMethodPrologue(lvar: LocalVariableInfo): InlineStackElement = adapter.run {
        val savedInlineDepth = currentInlineDepth
        if (lvar.isInlineCallMarker) {
            currentInlineDepth++
        } else {
            currentInlineDepth = 0
        }

        val suffix = "\$iv".repeat(currentInlineDepth)
        val inlineMethodName = lvar.inlineMethodName!!

        // If an extension function was inlined, `this_$iv` will point to class where extension
        // function was defined and `$this$<func-name>$iv` will show to virtual `this`, with
        // which function should really work.
        // Prefer the second variant if possible.
        val this_ = locals.activeVariables.firstOrNull { it.name == "\$this$$inlineMethodName$suffix" }
            ?: locals.activeVariables.firstOrNull { it.name == "this_$suffix" }
        val className = this_?.type?.className ?: ""

        val methodId = getPseudoMethodId(className.toCanonicalClassName(), inlineMethodName)

        // Start the `try {}` block around call, create distinct label for its beginning because
        // formally `visitTryCatchBlock()` call doesn't allow usage of visited labels.
        val tryStartLabel = Label()
        val tryEndsCatchBeginsLabel = Label()
        visitTryCatchBlock(tryStartLabel, tryEndsCatchBeginsLabel, tryEndsCatchBeginsLabel, null)
        visitLabel(tryStartLabel)
        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                processInlineMethodCall(inlineMethodName, methodId, this_)
            }
        )

        return InlineStackElement(
            lvar = lvar,
            methodId = methodId,
            tryEndsCatchBeginsLabel = tryEndsCatchBeginsLabel,
            inlineDepth = savedInlineDepth
        )
    }

    private fun emitInlinedMethodEpilogue(methodId: Int, tryEndsCatchBeginsLabel: Label) = adapter.run {
        val endOfMethodLabel = Label()
        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                processInlineMethodReturn(methodId)
            }
        )

        // Jump over the catch block if there is no exception
        visitJumpInsn(GOTO, endOfMethodLabel)

        // This is the border between `try {}` and `catch(Throwable)`
        visitLabel(tryEndsCatchBeginsLabel)
        // Stack: <exception>
        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                // Stack: <exception>
                dup()
                // Stack: <exception>, <exception>
                push(methodId)
                // Stack: <exception>, <exception>, <methodId>
                swap()
                // Stack: <exception>, <methodId>, <exception>
                invokeStatic(Injections::onInlineMethodCallException)
                // Stack: <exception>
            }
        )
        // Stack: <exception>
        // Rethrow
        visitInsn(ATHROW)

        // End of catch block: goto above goes here
        visitLabel(endOfMethodLabel)
    }

    private fun processInlineMethodCall(
        inlineMethodName: String,
        methodId: Int,
        owner: LocalVariableInfo?,
    ) = adapter.run {
        push(methodId)
        loadNewCodeLocationId()
        if (owner == null) {
            pushNull()
        } else {
            // As `owner` is an "old" variable index, we need to remap it, and `GeneratorAdapter.loadLocal()` bypass remapping
            visitVarInsn(owner.type.getOpcode(ILOAD), owner.index)
            box(owner.type)
        }
        invokeStatic(Injections::onInlineMethodCall)
        invokeBeforeEventIfPluginEnabled("inline method call $inlineMethodName in $methodName")
    }

    private fun processInlineMethodReturn(methodId: Int)  = adapter.run {
        push(methodId)
        invokeStatic(Injections::onInlineMethodCallReturn)
    }

    private fun getPseudoMethodId(possibleClassName: String, inlineMethodName: String): Int =
        TRACE_CONTEXT.getOrCreateMethodId(
            possibleClassName,
            inlineMethodName,
            "()V"
        )

    private fun topOfStackEndsBeforeLabel(label: Label): Boolean =
        inlineStack.isNotEmpty() && labelSorter.compare(inlineStack.last().lvar.endLabel, label) < 0

    private fun topOfStackEndsBeforeOrAtLabel(label: Label): Boolean =
        inlineStack.isNotEmpty() && labelSorter.compare(inlineStack.last().lvar.endLabel, label) <= 0

    // Don't support atomicfu for now, it is messed with stack
    // Maybe we will need to expand it later
    // Check inlined method name by marker variable name
    // TODO Find out what the exact problem is here with "recoverStackTrace"
    private fun isSupportedInline(lvar: LocalVariableInfo) = !lvar.name.endsWith("\$atomicfu")
            && lvar.inlineMethodName != "recoverStackTrace"

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
