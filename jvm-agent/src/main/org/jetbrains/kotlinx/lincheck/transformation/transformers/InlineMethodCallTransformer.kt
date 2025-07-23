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

private data class InlineStackElement(val methodId: Int, val catchLabel: Label, val lvar: LocalVariableInfo)

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

    private val localVarInlineStartComparator = Comparator<LocalVariableInfo>
        { a, b -> labelSorter.compare(a.labelIndexRange.second, b.labelIndexRange.second) }
        .thenComparing { it.index }

    private val inlineStack = mutableListOf<InlineStackElement>()
    private val pendingCatchBlocks = mutableMapOf<Int, Label>()

    override fun visitLabel(label: Label) = adapter.run {
        if (!locals.hasInlines || looksLikeSuspendMethod) {
            super.visitLabel(label)
            return
        }

        System.err.println(">>> $className.$methodName: LABEL $label STARTS")

        // Can one start label mark the beginning of several inlined methods?
        // One end label can mark several returns for sure.
        // Support multiple starts to be sure.

        // Sort starts by end labels (it provides proper nesting) and then variable slots (it provides stable sort)

        for (lvar in locals.inlinesStartAt(label)
            .filter { it.inlineMethodName != methodName && isSupportedInline(it) }
            .sortedWith(localVarInlineStartComparator)
        ) {
            val suffix = "\$iv".repeat(inlineStack.size + 1)
            val inlineName = lvar.inlineMethodName!!

            processStartLabel(label, lvar)
        }

        val lvar = locals.inlinesStartAt(label).firstOrNull()
        // Sometimes Kotlin compiler generate "inline marker" inside inline function itself, which
        // covers the whole function. Skip this, there is no "inlined call"
        if (lvar != null && lvar.inlineMethodName != methodName && isSupportedInline(lvar)) {
            val suffix = "\$iv".repeat(inlineStack.size + 1)
            val inlineName = lvar.inlineMethodName!!

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

            // We should wrap this "call" into try {} finally {} to properly
            // register exit from an inline function call via exception.
            // Such construction cannot be conditional

            val startLabel = Label()
            val endLabel = Label()
            visitTryCatchBlock(startLabel, endLabel, endLabel, null)
            visitLabel(startLabel)

            var methodId: Int = -1
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    System.err.println("=== ADD ${lvar.name} (${lvar.labelIndexRange.first} .. ${lvar.labelIndexRange.second})")
                    methodId = processInlineMethodCall(className, inlineName, clazz, thisLocal, label)
                }
            )

            // Create virtual static stack element
            inlineStack.add(InlineStackElement(
                methodId = methodId,
                catchLabel = endLabel,
                lvar = lvar
            ))

            super.visitLabel(label)
            System.err.println("<<< $className.$methodName: LABEL $label ENDS")
            return
        }

        // One label could end several inline calls
        val topOfStack = inlineStack.lastOrNull()
        if (topOfStack?.lvar?.labelIndexRange?.second == label) {
            // Visit the label before all "exit" code as there could be jumps to this label to exit from this method.
            // Jump instrumentation code doesn't insert exits for such jumps, as it will duplicate to a lot of code.
            visitLabel(label)

            var removeFromStack = 0
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    removeFromStack = exitFromInlineMethods("Exit with LABEL") { it.labelIndexRange.second != label }
                }
            )
            // It is a true end of these inline methods, remove them from the static stack.
            repeat(removeFromStack) {
                val topOfStack = inlineStack.removeLast()
                System.err.println("=== REMOVE ${topOfStack.lvar.name} (${topOfStack.lvar.labelIndexRange.first} .. ${topOfStack.lvar.labelIndexRange.second})")
            }
            System.err.println("<<< $className.$methodName: LABEL $label ENDS")
            return
        }
        System.err.println("<<< $className.$methodName: LABEL $label ENDS")

        super.visitLabel(label)
    }

    var exits = 0

    override fun visitJumpInsn(opcode: Int, label: Label) = adapter.run {
        // Maybe we jump out of the inline stack?
        val topOfStack = inlineStack.lastOrNull()
        // we must not pop the static analysis stack here, as an inline method is not ended here!
        if (topOfStack != null && labelSorter.compare(topOfStack.second.labelIndexRange.second, label) <= 0) {
            System.err.println("<<< $className.$methodName: JUMP $opcode TO $label STARTS")
            when (opcode) {
                GOTO -> invokeIfInAnalyzedCode(
                    original = {},
                    instrumented = {
                        exitFromInlineMethods("Exit with GOTO") { lvar -> labelSorter.compare(lvar.labelIndexRange.second, label) <= 0 }
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
                        exitFromInlineMethods("Exit with IF ($opcode)") { lvar -> labelSorter.compare(lvar.labelIndexRange.second, label) <= 0 }
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
                        exitFromInlineMethods("Exit with IF_xCMP ($opcode)") { lvar -> labelSorter.compare(lvar.labelIndexRange.second, label) <= 0 }
                    }
                }
                JSR -> Unit
                else -> error("Unknown conditional opcode $opcode")
            }
            System.err.println("<<< $className.$methodName: JUMP $opcode TO $label ENDS")
        }
        visitJumpInsn(opcode, label)
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        when (opcode) {
            ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN, ATHROW -> {
                // we must not pop the static analysis stack here, as an inline method is not ended here!
                if (inlineStack.isNotEmpty()) {
                    System.err.println("<<< $className.$methodName: RETURN $opcode STARTS")
                    invokeIfInAnalyzedCode(
                        original = {},
                        instrumented = {
                            exitFromInlineMethods("Exit with RETURN ($opcode)") { false }
                        }
                    )
                    System.err.println("<<< $className.$methodName: RETURN $opcode END")
                }
            }
        }
        visitInsn(opcode)
    }

    private fun exitFromInlineMethods(msg: String, stopHere: (LocalVariableInfo) -> Boolean): Int {
        var removed = 0
        for (topOfStack in inlineStack.reversed()) {
            val (className, lvar) = topOfStack
            // This inline call covers this jump, stop "exiting"
            if (stopHere(lvar)) {
                System.err.println("+++ BREAK AT ${lvar.name} (${lvar.labelIndexRange.first} .. ${lvar.labelIndexRange.second})")
                break
            }
            System.err.println("*** EXIT ${lvar.name} (${lvar.labelIndexRange.first} .. ${lvar.labelIndexRange.second})")
            adapter.push("~~~ " + msg + " - " + lvar.inlineMethodName)
            adapter.invokeStatic(Injections::debugPrint)
            processInlineMethodCallReturn(className, lvar.inlineMethodName!!, lvar.labelIndexRange.first)
            removed++
        }
        return removed
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
    private fun processInlineMethodCall(className: String?, inlineMethodName: String, ownerType: Type?, owner: Int?, startLabel: Label): Int = adapter.run {
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
        TRACE_CONTEXT.getOrCreateMethodId(possibleClassName ?: "$className\$$methodName\$$startLabel\$inlineCall", inlineMethodName, "()V")

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
