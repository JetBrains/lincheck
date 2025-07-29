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
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.EventTracker
import sun.nio.ch.lincheck.Injections

private data class InlineStackElement(
    val lvar: LocalVariableInfo,
    val methodId: Int,
    val tryEndsCatchBeginsLabel: Label
)

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

    // Sort local variables by their end labels (earlier label goes first)
    // and then by index if labels are the same (to have a stable result).
    private val localVarInlineStartComparator = Comparator<LocalVariableInfo>
    { a, b -> labelSorter.compare(a.endLabel, b.endLabel) }
        .thenComparing { it.index }

    private val inlineStack = mutableListOf<InlineStackElement>()

    override fun visitLabel(label: Label) = adapter.run {
        if (!locals.hasInlines || looksLikeSuspendMethod) {
            super.visitLabel(label)
            return
        }

        System.err.println(">>> $className.$methodName: LABEL $label STARTS")
        // It is not clear, could one label be used to mark the end of one inline call and
        // the start of another one. Nothing wrong instrument exists first.
        if (topOfStackEndsBeforeOrAtLabel(label)) {
            var removeFromStack = 0
            invokeIfInAnalyzedCode(original = {}, instrumented = {
                removeFromStack = exitFromInlineMethods("Exit with LABEL") {
                    val cmp = labelSorter.compare(it.endLabel, label)
                    if (cmp < 0) {
                        Logger.warn { "$className.$methodName: Local call to ${it.inlineMethodName} should be finished at label ${it.endLabel} but still alive at ${label}." }
                    }
                    // Stop processing stack if a label is before the end label
                    cmp > 0
                }
            })

            // Add `catch(t: Throwable) { reportException(); throw t }` for all exited methods
            repeat(removeFromStack) {
                // pop static stack
                val (lvar, methodId, tryEndsCatchBeginsLabel) = inlineStack.removeLast()

                System.err.println("=== REMOVE ${lvar.name} (${lvar.startLabel} .. ${lvar.endLabel}) at $label")

                // Jump over "catch" to the end-of-inline-call label (given one)
                // It will jump over all generated `catch` blocks in once, but it is Ok.
                push("Jump Over Catch for $methodId")
                invokeStatic(Injections::debugPrint)
                visitJumpInsn(GOTO, label)

                // This is the border between `try {}` and `catch(Throwable)`
                visitLabel(tryEndsCatchBeginsLabel)
                dup()
                push(methodId)
                swap()
                invokeStatic(Injections::onInlineMethodCallException)
                push("Rethrow exception from catch-all of inline method $methodId")
                invokeStatic(Injections::debugPrint)
                visitInsn(ATHROW)
            }
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
            // Start the `try {}` block around call, create distinct label for its beginning because
            // formally `visitTryCatchBlock()` call doesn't allow usage of visited labels.
            val tryStartLabel = Label()
            val tryEndsCatchBeginsLabel = Label()
            visitTryCatchBlock(tryStartLabel, tryEndsCatchBeginsLabel, tryEndsCatchBeginsLabel, null)
            visitLabel(tryStartLabel)

            val suffix = "\$iv".repeat(inlineStack.size + 1)
            val inlineName = lvar.inlineMethodName!!

            // If an extension function was inlined, `this_$iv` will point to class where extension
            // function was defined and `$this$<func-name>$iv` will show to virtual `this`, with
            // which function should really work.
            // Prefer the second variant if possible.
            val this_ =
                locals.activeVariables.firstOrNull { it.name == "\$this$$inlineName$suffix" }
                    ?: locals.activeVariables.firstOrNull { it.name == "this_$suffix" }
            val clazz = this_?.type
            val className = if (clazz?.sort == OBJECT) clazz.className else null
            val thisLocal = if (clazz?.sort == OBJECT) this_.index else null

            var methodId: Int = -1
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    methodId = processInlineMethodCall(className, inlineName, clazz, thisLocal, label)
                }
            )
            System.err.println("=== ADD ${lvar.name} (${lvar.startLabel} .. ${lvar.endLabel}) at $label")
            inlineStack.add(
                InlineStackElement(
                    lvar = lvar,
                    methodId = methodId,
                    tryEndsCatchBeginsLabel = tryEndsCatchBeginsLabel
                )
            )
        }
        System.err.println("<<< $className.$methodName: LABEL $label ENDS")
    }

    override fun visitJumpInsn(opcode: Int, label: Label) = adapter.run {
        // This is a jump inside the current inline call
        if (!topOfStackEndsBeforeLabel(label)) {
            visitJumpInsn(opcode, label)
            return
        }
        // we must not pop the static analysis stack here, as an inline method is not ended here!
        // So, only generate injection and don't alter the stack
        System.err.println(">>> $className.$methodName: JUMP $opcode TO $label STARTS")
        when (opcode) {
            GOTO -> invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    // Stop when we jump inside the inline method, comparison is strict as
                    // endLabel will be processed by `visitLabel()` method
                    exitFromInlineMethods("Exit with GOTO") { labelSorter.compare(it.endLabel, label) > 0 }
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
                    // Stop when we jump inside the inline method, comparison is strict as
                    // endLabel will be processed by `visitLabel()` method
                    exitFromInlineMethods("Exit with IF ($opcode)") { labelSorter.compare(it.endLabel, label) > 0 }
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
                    exitFromInlineMethods("Exit with IF_xCMP ($opcode)") {
                        labelSorter.compare(
                            it.endLabel,
                            label
                        ) <= 0
                    }
                }
            }

            JSR -> Unit
            else -> error("Unknown conditional opcode $opcode")
        }
        System.err.println("<<< $className.$methodName: JUMP $opcode TO $label ENDS")
        visitJumpInsn(opcode, label)
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        if (inlineStack.isEmpty()) {
            visitInsn(opcode)
            return
        }
        System.err.println(">>> $className.$methodName: RETURN ($opcode) STARTS")
        // we must not pop the static analysis stack here, as an inline method is not ended here!
        // So, only generate injection and don't alter the stack
        when (opcode) {
            // Don't process ATHROW, it will be processed in catch blocks
            ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                if (inlineStack.isNotEmpty()) {
                    System.err.println("<<< $className.$methodName: RETURN $opcode STARTS")
                    invokeIfInAnalyzedCode(
                        original = {},
                        instrumented = {
                            // Process the whole stack, so never break out of loop
                            exitFromInlineMethods("Exit with RETURN ($opcode)") { false }
                        }
                    )
                    System.err.println("<<< $className.$methodName: RETURN $opcode END")
                }
            }
        }
        System.err.println("<<< $className.$methodName: RETURN ($opcode) ENDS")
        visitInsn(opcode)
    }

    private fun exitFromInlineMethods(msg: String, stopHere: (LocalVariableInfo) -> Boolean): Int {
        var exited = 0
        for (topOfStack in inlineStack.reversed()) {
            val (lvar, methodId) = topOfStack
            // This inline call covers this jump, stop "exiting"
            if (stopHere(lvar)) {
                System.err.println("=== STOP PROCESSING AT ${lvar.name} (${lvar.startLabel} .. ${lvar.endLabel})")
                break
            }
            System.err.println("*** EXIT ${lvar.name} (${lvar.startLabel} .. ${lvar.endLabel}): $msg")
            adapter.push("~~~ " + msg + " - " + lvar.inlineMethodName)
            adapter.invokeStatic(Injections::debugPrint)
            processInlineMethodCallReturn(methodId)
            exited++
        }
        return exited
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        if (inlineStack.isNotEmpty()) {
            Logger.error {
                "Inline methods calls are not balanced at $className.$methodName:\n" +
                        inlineStack.reversed().joinToString(separator = "\n") {
                            val (lvar, methodId) = it
                            "  ${lvar.inlineMethodName} (slot ${lvar.index}, methodId $methodId) called at label ${lvar.startLabel}"
                        }
            }
            exitFromInlineMethods("Exit with END OF PARENT") { false }
        }
        super.visitMaxs(maxStack, maxLocals)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun processInlineMethodCall(
        className: String?,
        inlineMethodName: String,
        ownerType: Type?,
        owner: Int?,
        startLabel: Label
    ): Int = adapter.run {
        // Create a fake method descriptor
        val methodId = getPseudoMethodId(className, startLabel, inlineMethodName)
        System.err.println("@@@ $className / $startLabel / $inlineMethodName : $methodId")
        push(methodId)
        loadNewCodeLocationId()
        if (owner == null) {
            pushNull()
        } else {
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

        return methodId
    }

    private fun processInlineMethodCallReturn(inlineMethodName: String, startLabel: Label) = adapter.run {
        // Create a fake method descriptor
        val methodId = getPseudoMethodId(className, startLabel, inlineMethodName)
        System.err.println("%%% $className / $startLabel / $inlineMethodName : $methodId")
        push(methodId)
        invokeStatic(Injections::onInlineMethodCallReturn)
    }

    private fun getPseudoMethodId(possibleClassName: String?, startLabel: Label, inlineMethodName: String): Int =
        TRACE_CONTEXT.getOrCreateMethodId(
            possibleClassName ?: "$className\$$methodName\$$startLabel\$inlineCall",
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
