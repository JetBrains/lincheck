/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.transformers

import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.*
import org.jetbrains.lincheck.util.*
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.Injections

/**
 * [LoopTransformer] tracks loops enter/exit points and new loop iterations starting points.
 */
internal class LoopTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : InstructionMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {

    // Retrieve loop sites planned from the precomputed basic-block CFG.
    private val loopInfo = methodInfo.basicControlFlowGraph.loopInfo.ensureNotNull {
        "Loops information is not available for method $className.$methodName$descriptor"
    }

    // Map from the first loop header instruction index to loopId.
    private val iterationEntrySites: Map<InstructionIndex, LoopId> =
        methodInfo.basicControlFlowGraph.computeIterationEntrySites(loopInfo)

    // Map from a normal exit instruction index to the set of exited loopIds.
    private val normalExitSites: Map<InstructionIndex, Set<LoopId>> =
        methodInfo.basicControlFlowGraph.computeNormalExitSites(loopInfo)

    // Map from an exception handler label block to the set of exited loopIds.
    private val exceptionExitSites: Map<InstructionIndex, Set<LoopId>> =
        methodInfo.basicControlFlowGraph.computeExceptionExitSites(loopInfo)

    override fun beforeInsn(index: Int, opcode: Int): Unit = adapter.run {
        // Inject `onLoopIteration` at the loop header on every iteration (including the first).
        iterationEntrySites[index]?.let { loopId ->
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    // STACK: <empty>
                    loadNewCodeLocationId()
                    adapter.push(loopId)
                    // STACK: codeLocation, loopId
                    adapter.invokeStatic(Injections::onLoopIteration)
                    // STACK: <empty>
                }
            )
        }

        // Inject `onLoopExit` on transitions from within the loop body to outside.
        normalExitSites[index]?.let { loopIds ->
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    for (loopId in loopIds) {
                        // conservative default without exclusivity analysis
                        // TODO: implement exclusivity analysis and use it here
                        val isReachableFromOutsideLoop = true
                        // STACK: <empty>
                        loadNewCodeLocationId()
                        adapter.push(loopId)
                        pushNull()
                        push(isReachableFromOutsideLoop)
                        // STACK: codeLocation, loopId, null, isReachableFromOutsideLoop
                        adapter.invokeStatic(Injections::afterLoopExit)
                        // STACK: <empty>
                    }
                }
            )
        }

        // Inject `onLoopExit` on exceptional transitions from within the loop body to outside exception handlers.
        exceptionExitSites[index]?.let { loopIds ->
            invokeIfInAnalyzedCode(
                original = { },
                instrumented = {
                    // At handler entry, the thrown exception object is on the stack.
                    // Store it to a temp local, emit injections, then restore it for original bytecode.
                    // val exceptionLocal = newLocal(THROWABLE_TYPE)
                    // storeLocal(exceptionLocal)
                    for (loopId in loopIds) {
                        // conservative default without exclusivity analysis
                        // TODO: implement exclusivity analysis and use it here
                        val isReachableFromOutsideLoop = true
                        // STACK: <empty>
                        loadNewCodeLocationId()
                        push(loopId)
                        pushNull()
                        // loadLocal(exceptionLocal)
                        push(isReachableFromOutsideLoop)
                        // STACK: codeLocation, loopId, exception, isReachableFromOutsideLoop
                        invokeStatic(Injections::afterLoopExit)
                        // STACK: <empty>
                    }
                    // Restore the exception object back to the stack for the handler body (e.g., ASTORE)
                    // loadLocal(exceptionLocal)
                }
            )
        }
    }
}

private fun BasicBlockControlFlowGraph.computeIterationEntrySites(
    loopInfo: MethodLoopsInformation
): Map<InstructionIndex, Int> {
    if (!loopInfo.hasLoops()) return emptyMap()
    val cfg = this
    val result = mutableMapOf<InstructionIndex, Int>()
    for (loop in loopInfo.loops) {
        val idx = cfg.firstOpcodeIndexOf(loop.header) ?: continue
        // If multiple loops share the same header opcode index (rare),
        // prefer the inner loop by letting the later put override only if absent.
        result.putIfAbsent(idx, loop.id)
    }
    return result
}

private fun BasicBlockControlFlowGraph.computeNormalExitSites(
    loopInfo: MethodLoopsInformation
): Map<InstructionIndex, Set<Int>> {
    if (!loopInfo.hasLoops()) return emptyMap()
    val cfg = this
    val result = mutableMapOf<InstructionIndex, MutableSet<Int>>()
    for (loop in loopInfo.loops) {
        for (e in loop.normalExits) {
            // By cfg/loop invariants every normal exit is decided by the first real opcode of the target block.
            val insnIndex: InstructionIndex = cfg.firstOpcodeIndexOf(e.target) ?: continue
            result.updateInplace(insnIndex, default = mutableSetOf()) { add(loop.id) }
        }
    }
    return result.mapValues { it.value.toSet() }
}

private fun BasicBlockControlFlowGraph.computeExceptionExitSites(
    loopInfo: MethodLoopsInformation
): Map<InstructionIndex, Set<Int>> {
    if (!loopInfo.hasLoops()) return emptyMap()
    val cfg = this
    val result = mutableMapOf<InstructionIndex, MutableSet<Int>>()
    for (loop in loopInfo.loops) {
        for (handlerBlock in loop.exceptionalExitHandlers) {
            val insnIndex: InstructionIndex = cfg.firstOpcodeIndexOf(handlerBlock) ?: continue
            result.updateInplace(insnIndex, default = mutableSetOf()) { add(loop.id) }
        }
    }
    return result.mapValues { it.value.toSet() }
}