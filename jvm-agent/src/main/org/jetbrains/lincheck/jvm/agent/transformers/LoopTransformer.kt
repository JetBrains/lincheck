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
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.Injections

/**
 * LoopTransformer tracks loop start of every loop iteration and loop exit points.
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
    private val loopInfo = methodInfo.basicControlFlowGraph?.loopInfo.ensureNotNull {
        "Loops information is not available for method $className.$methodName $descriptor"
    }

    // remapping from normal to non-phony instruction indices
    private val insnIndexRemapping: IntArray =
        methodInfo.basicControlFlowGraph!!.computeInstructionIndicesRemapping()

    /*
     * Why non-phony instruction indexing is used here.

     * This transformer is placed at the beginning of the transformers' chain (see [LincheckClassVisitor]),
     * but after the analyzers. Normally, these analyzers placed before it should not insert new bytecode,
     * so the instruction indices stored in the precomputed CFG (built for the original bytecode)
     * would match the indices we observe while visiting the method.
     *
     * However, ASM's [AnalyzerAdapter] used by our type analysis does insert additional bytecode instructions —
     * specifically [Label] instructions — while visiting [Opcodes.NEW] instructions.
     *
     * Label instructions (as well as line number and frame instructions) are "phony" instructions:
     * they are present in the instructions' list and affect the normal instruction indices,
     * but they do not correspond to real opcodes executed by the JVM.
     * Because [AnalyzerAdapter] may add such labels before [LoopTransformer] runs, relying on the
     * normal instruction indices would make our injection points drift from the indices recorded in the CFG.
     *
     * To overcome this problem, we operate on non-phony instruction indices instead:
     * we compute and use an index that only counts real opcodes.
     * This protects us against instructions inserted by [AnalyzerAdapter],
     * as well as in general against the insertion of any kind of phony instructions by other bytecode visitors.
     */

    // Map from the first loop header non-phony instruction index to loopId.
    private val iterationEntrySites: Map<InstructionIndex, LoopId> =
        methodInfo.basicControlFlowGraph!!.computeIterationEntrySites(insnIndexRemapping, loopInfo)

    // Map from a normal exit non-phony instruction index to the set of exited loopIds.
    private val normalExitSites: Map<InstructionIndex, List<LoopId>> =
        methodInfo.basicControlFlowGraph!!.computeNormalExitSites(insnIndexRemapping, loopInfo)

    // Map from an exceptional exit non-phony instruction index to the set of exited loopIds.
    private val exceptionExitSites: Map<InstructionIndex, List<LoopId>> =
        methodInfo.basicControlFlowGraph!!.computeExceptionExitSites(insnIndexRemapping, loopInfo)

    // Map from normal and exceptional exits non-phony instruction index to the set of loopIds for which it is reachable from outside them.
    private val opcodesReachableFromOutsideLoops: Map<InstructionIndex, Set<LoopId>> =
        methodInfo.basicControlFlowGraph!!.computeReachabilityFromOutsideLoops(insnIndexRemapping, loopInfo)

    override fun beforeInsn(index: Int, opcode: Int): Unit = adapter.run {
        val nonPhonyIndex = currentNonPhonyInsnIndex

        // Inject `onLoopIteration` at the loop header on every iteration (including the first).
        iterationEntrySites[nonPhonyIndex]?.let { loopId ->
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
        normalExitSites[nonPhonyIndex]?.let { loopIds ->
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    for (loopId in loopIds) {
                        val isReachableFromOutsideLoop = opcodesReachableFromOutsideLoops[nonPhonyIndex]?.contains(loopId) ?: true
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
        exceptionExitSites[nonPhonyIndex]?.let { loopIds ->
            invokeIfInAnalyzedCode(
                original = { },
                instrumented = {
                    // At handler entry, the thrown exception object is on the stack.
                    // Store it to a temp local, emit injections, then restore it for original bytecode.
                    // val exceptionLocal = newLocal(THROWABLE_TYPE)
                    // storeLocal(exceptionLocal)
                    for (loopId in loopIds) {
                        val isReachableFromOutsideLoop = opcodesReachableFromOutsideLoops[nonPhonyIndex]?.contains(loopId) ?: true
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

/**
 * Computes remapping from normal instruction indices (including labels/lines/frames)
 * to non-phony instruction indices (only real opcodes).
 * For phony entries, the value is -1.
 */
private fun BasicBlockControlFlowGraph.computeInstructionIndicesRemapping(): IntArray {
    val map = IntArray(instructions.size()) { -1 }
    var nonPhony = -1
    instructions.forEachIndexed { i, insn ->
        // ASM uses opcode == -1 for pseudo instructions (Label, LineNumber, Frame)
        map[i] = if (insn.opcode != -1) ++nonPhony else -1
    }
    return map
}

private fun BasicBlockControlFlowGraph.computeIterationEntrySites(
    insnIndexRemapping: IntArray,
    loopInfo: MethodLoopsInformation,
): Map<InstructionIndex, Int> {
    if (!loopInfo.hasLoops()) return emptyMap()
    val cfg = this
    val result = mutableMapOf<InstructionIndex, Int>()
    for (loop in loopInfo.loops) {
        val idx = cfg.firstOpcodeIndexOf(loop.header) ?: continue
        // If multiple loops share the same header opcode index (rare),
        // prefer the inner loop by letting the later put override only if absent.
        result.putIfAbsent(insnIndexRemapping[idx], loop.id)
    }
    return result
}

private fun BasicBlockControlFlowGraph.computeNormalExitSites(
    insnIndexRemapping: IntArray,
    loopInfo: MethodLoopsInformation,
): Map<InstructionIndex, List<Int>> {
    if (!loopInfo.hasLoops()) return emptyMap()
    val cfg = this
    val result = mutableMapOf<InstructionIndex, MutableSet<Int>>()
    for (loop in loopInfo.loops) {
        for (e in loop.normalExits) {
            // By cfg/loop invariants every normal exit is decided by the first real opcode of the target block.
            val idx = cfg.firstOpcodeIndexOf(e.target) ?: continue
            result.updateInplace(insnIndexRemapping[idx], default = mutableSetOf()) { add(loop.id) }
        }
    }
    // We reverse the order of all loop ids, because when we insert `afterLoopExit`
    // into the normal exit, we need to do that from innermost loop to outermost.
    // And the inner loop will have a bigger id than its outer loop.
    return result.mapValues { it.value.reversed() }
}

private fun BasicBlockControlFlowGraph.computeExceptionExitSites(
    insnIndexRemapping: IntArray,
    loopInfo: MethodLoopsInformation,
): Map<InstructionIndex, List<Int>> {
    if (!loopInfo.hasLoops()) return emptyMap()
    val cfg = this
    val result = mutableMapOf<InstructionIndex, MutableSet<Int>>()
    for (loop in loopInfo.loops) {
        for (handlerBlock in loop.exceptionalExitHandlers) {
            val idx = cfg.firstOpcodeIndexOf(handlerBlock) ?: continue
            result.updateInplace(insnIndexRemapping[idx], default = mutableSetOf()) { add(loop.id) }
        }
    }
    // We reverse the order of all loop ids, because when we insert `afterLoopExit`
    // into the exception handler, we need to do that from innermost loop to outermost.
    // And the inner loop will have a bigger id than its outer loop.
    return result.mapValues { it.value.reversed() }
}

/**
 * Computes a map from opcode index to the set of loop ids.
 * For each opcode it stores all loops for which it is reachable from outside them.
 */
private fun BasicBlockControlFlowGraph.computeReachabilityFromOutsideLoops(
    insnIndexRemapping: IntArray,
    loopInfo: MethodLoopsInformation
): Map<InstructionIndex, Set<Int>> {
    if (!loopInfo.hasLoops()) return emptyMap()
    val cfg = this
    val result = mutableMapOf<InstructionIndex, MutableSet<Int>>()
    for (loop in loopInfo.loops) {
        val exitBlocks = (loop.normalExits.asSequence().map { it.target } + loop.exceptionalExitHandlers.asSequence())
        for (exit in exitBlocks) {
            val idx = cfg.firstOpcodeIndexOf(exit) ?: continue
            result.updateInplace(insnIndexRemapping[idx], default = mutableSetOf()) {
                if (exit !in loop.exclusiveExits) {
                    add(loop.id)
                }
            }
        }
    }
    return result.mapValues { it.value.toSet() }
}