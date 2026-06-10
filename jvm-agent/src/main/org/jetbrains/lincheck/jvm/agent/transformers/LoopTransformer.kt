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

import org.objectweb.asm.Opcodes
import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.jvm.agent.analysis.isSafeMethodCall
import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.*
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.util.*
import org.jetbrains.lincheck.util.collections.*
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.VarInsnNode
import sun.nio.ch.lincheck.*

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
    context: TraceContext,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
    val shouldTrackIrreducibleLoops: Boolean,
) : InstructionMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor) {

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

    // Map from loopId to the set of back-edge source blocks that have an await path from the header.
    private val awaitPathBackEdgeSources: Map<LoopId, Set<BasicBlockIndex>> =
        methodInfo.basicControlFlowGraph!!.computeAwaitPathBackEdgeSources(loopInfo)

    // Map from the first loop header non-phony instruction index to the list of loopIds having this header.
    private val loopIdsByHeaderNonPhonyIndex: Map<InstructionIndex, List<LoopId>> =
        methodInfo.basicControlFlowGraph!!.computeLoopIdsByHeaderNonPhonyIndex(insnIndexRemapping, loopInfo)

    // Map from loopId to the code location of its header.
    private val codeLocationIdByLoopId = mutableMapOf<LoopId, Int>()

    // Map from a non-phony instruction index (the last opcode of a clean back-edge source block)
    // to the loopId. These are the sites where `onAwaitLoopPath` should be injected.
    private val awaitPathInjectionLocations: Map<InstructionIndex, List<LoopId>> =
        methodInfo.basicControlFlowGraph!!.computeAwaitPathInjectionLocations(insnIndexRemapping, awaitPathBackEdgeSources)

    override fun beforeInsn(index: Int, opcode: Int): Unit = adapter.run {
        val nonPhonyIndex = currentNonPhonyInsnIndex

        // Compute header code locations, so we can reuse them for both `onLoopIteration` and `onAwaitLoopPath` injections.
        loopIdsByHeaderNonPhonyIndex[nonPhonyIndex]?.let { loopIds ->
            val canonicalId = context.codeLocationsPool.register(createCurrentLoopHeaderCodeLocation(loopIds))
            for (loopId in loopIds) {
                codeLocationIdByLoopId.putIfAbsent(loopId, canonicalId)
            }
        }

        // Inject basic onLoopIteration at the header.
        // Inject `onLoopExit` on transitions from within the loop body to outside.
        // This must be done before `onLoopIteration` to correctly handle consecutive loops
        // where the exit target of one loop coincides with the header of the next loop.
        normalExitSites[nonPhonyIndex]?.let { loopIds ->
            for (loopId in loopIds) {
                val isIrreducible = loopInfo.getLoopInfo(loopId)?.isIrreducible ?: true
                if (isIrreducible && !shouldTrackIrreducibleLoops) continue

                val isReachableFromOutsideLoop = opcodesReachableFromOutsideLoops[nonPhonyIndex]
                    ?.contains(loopId) ?: true
                // STACK: <empty>
                invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
                loadNewCodeLocationId(createCurrentLineCodeLocation())
                adapter.push(loopId)
                pushNull()
                push(isReachableFromOutsideLoop)
                // STACK: descriptor, codeLocation, loopId, null, isReachableFromOutsideLoop
                adapter.invokeStatic(Injections::afterLoopExit)
                // STACK: <empty>
            }

        }
        // Inject `onLoopIteration` at the loop header on every iteration (including the first).
        iterationEntrySites[nonPhonyIndex]?.let { loopId ->
            val isReducible = loopInfo.getLoopInfo(loopId)?.isReducible ?: false
            // STACK: <empty>
            invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
            adapter.push(codeLocationIdByLoopId.getValue(loopId))
            adapter.push(loopId)
            // STACK: descriptor, codeLocation, loopId
            if (isReducible) {
                adapter.invokeStatic(Injections::onLoopIteration)
            } else if (shouldTrackIrreducibleLoops) {
                adapter.invokeStatic(Injections::onIrreducibleLoopIteration)
            }
            // STACK: <empty>
        }

        // Inject 'onAwaitLoopPath' before the back edge if this loop has await paths.
        awaitPathInjectionLocations[nonPhonyIndex]?.let { loopIds ->
            for (loopId in loopIds) {
                val isReducible = loopInfo.getLoopInfo(loopId)?.isReducible ?: false
                if (!isReducible) continue

                // STACK: <empty>
                invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
                adapter.push(codeLocationIdByLoopId.getValue(loopId))
                adapter.push(loopId)
                // STACK: descriptor, codeLocation, loopId
                adapter.invokeStatic(Injections::onAwaitLoopPath)
                // STACK: <empty>
            }
        }

        // Inject `onLoopExit` on exceptional transitions from within the loop body to outside exception handlers.
        exceptionExitSites[nonPhonyIndex]?.let { loopIds ->
            // At handler entry, the thrown exception object is on the stack.
            // Store it to a temp local, emit injections, then restore it for original bytecode.
            val exceptionLocal = newLocal(THROWABLE_TYPE)
            storeLocal(exceptionLocal)
            for (loopId in loopIds) {
                val isIrreducible = loopInfo.getLoopInfo(loopId)?.isIrreducible ?: true
                if (isIrreducible && !shouldTrackIrreducibleLoops) continue

                val isReachableFromOutsideLoop = opcodesReachableFromOutsideLoops[nonPhonyIndex]
                    ?.contains(loopId) ?: true
                // STACK: <empty>
                invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
                loadNewCodeLocationId(createCurrentLineCodeLocation())
                push(loopId)
                loadLocal(exceptionLocal)
                push(isReachableFromOutsideLoop)
                // STACK: descriptor, codeLocation, loopId, exception, isReachableFromOutsideLoop
                invokeStatic(Injections::afterLoopExit)
                // STACK: <empty>
            }
            // Restore the exception object back to the stack for the handler body (e.g., ASTORE)
            loadLocal(exceptionLocal)
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
): Map<InstructionIndex, LoopId> {
    if (!loopInfo.hasLoops()) return emptyMap()
    val cfg = this
    val result = mutableMapOf<InstructionIndex, LoopId>()
    for (loop in loopInfo.loops) {
        for (header in loop.headers) {
            val idx = cfg.firstOpcodeIndexOf(header) ?: continue
            // If multiple loops share the same header opcode index (rare),
            // prefer the inner loop by letting the later put override only if absent.
            result.putIfAbsent(insnIndexRemapping[idx], loop.id)
        }
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
        val exitBlocks = (
            loop.normalExits.asSequence().map { it.target } +
            loop.exceptionalExitHandlers.asSequence()
        )
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

/**
 * Compute injection locations for await paths
 * This should happen at the back edges of the source blocks on a path that can be considered an await path.
 * by back edge we mean the jump instruction that goes back to the loop header.
 *
 * Returns a map from non-phony instruction index to the loop id.
 */
private fun BasicBlockControlFlowGraph.computeAwaitPathInjectionLocations(
    insnIndexRemapping: IntArray,
    awaitPathBackEdges: Map<LoopId, Set<BasicBlockIndex>>,
): Map<InstructionIndex, List<LoopId>> {
    if (awaitPathBackEdges.isEmpty()) return emptyMap()
    val result = mutableMapOf<InstructionIndex, MutableSet<LoopId>>()
    for ((loopId, sourceBlocks) in awaitPathBackEdges) {
        for (block in sourceBlocks) {
            // Inject at the last opcode of the source block
            val idx = lastOpcodeIndexOf(block) ?: continue
            val nonPhonyIndex = insnIndexRemapping[idx]
            if (nonPhonyIndex >= 0) {
                result.getOrPut(nonPhonyIndex) { mutableSetOf() }.add(loopId)
            }
        }
    }
    return result.mapValues { (_, loopIds) -> loopIds.sortedDescending() }
}

private fun BasicBlockControlFlowGraph.computeLoopIdsByHeaderNonPhonyIndex(
    insnIndexRemapping: IntArray,
    loopInfo: MethodLoopsInformation,
): Map<InstructionIndex, List<LoopId>> {
    if (!loopInfo.hasLoops()) return emptyMap()
    val result = mutableMapOf<InstructionIndex, MutableList<LoopId>>()
    for (loop in loopInfo.loops) {
        val idx = firstOpcodeIndexOf(loop.header) ?: continue
        val nonPhonyIndex = insnIndexRemapping[idx]
        if (nonPhonyIndex >= 0) {
            result.getOrPut(nonPhonyIndex) { mutableListOf() }.add(loop.id)
        }
    }
    return result
}

/**
 * An await path is a path from a loop header to a back edge source such that:
 *   - no shared writes (field/array writes), monitor operations, or side-effecting calls (except `Thread.onSpinWait`) are present on that path
 *   - at least one shared read (field/array read) is present on that path
 *
 * Awaitness is a property of a path, not of the whole loop. A loop that contains side effects
 * on some paths can still have an await path if at least one back-edge path satisfies the conditions above.
 *
 * Examples:
 * ```
 * // Simple busy-wait - the whole loop body has no side effects, there is a shared read and a call to `Thread.onSpinWait'
 * while (!flag.get()) {
 *     Thread.onSpinWait()
 * }
 *
 * // Complex loop with CAS and writes on some paths, but a read-only spin-retry path
 * _state.loop { state ->
 *     val element = array[head].value
 *     if (element == null) return@loop   // <-- await path
 *     if (_state.compareAndSet(old, new)) { ... return result }
 * }
 * ```
 *
 * The instrumentation with the await paths of the two examples would look like this (ignoring the exit injections for simplicity):
 * ```
 * while (!flag.get()) {
 *     Injections.onLoopIteration(descriptor, loopHeaderCodeLocation, loopId)
 *     Thread.onSpinWait()
 *     Injections.onAwaitLoopPath(descriptor, loopHeaderCodeLocation, loopId) // before the back edge
 * }
 *
 * _state.loop { state ->
 *     Injections.onLoopIteration(descriptor, loopHeaderCodeLocation, loopId)
 *     val element = array[head].value
 *     if (element == null) {
 *         Injections.onAwaitLoopPath(descriptor, loopHeaderCodeLocation, loopId)
 *         return@loop // we consider that the back edge goes from the return to the loop header, so we inject before the return
 *     }
 *     if (_state.compareAndSet(old, new)) { ... return result }
 * }
 * ```
 */

private const val ON_SPIN_WAIT_METHOD_NAME = "onSpinWait"
private const val ON_SPIN_WAIT_METHOD_DESCRIPTOR = "()V"

//TODO: Consider moving this whitelist to `ConditionSafetyChecker`
private val ATOMIC_SIDE_EFFECT_FREE_GET_METHODS = setOf(
    "java/util/concurrent/atomic/AtomicBoolean.get",
    "java/util/concurrent/atomic/AtomicInteger.get",
    "java/util/concurrent/atomic/AtomicLong.get",
    "java/util/concurrent/atomic/AtomicReference.get",
    "java/util/concurrent/atomic/AtomicLongArray.get",
    "java/util/concurrent/atomic/AtomicReferenceArray.get",
    "java/lang/invoke/VarHandle.get",
    "java/lang/invoke/VarHandle.getVolatile",
    "java/lang/invoke/VarHandle.getAcquire",
    "java/lang/invoke/VarHandle.getOpaque",
    "org/jctools/util/UnsafeLongArrayAccess.lvLongElement",
    "org/jctools/util/UnsafeRefArrayAccess.lvRefElement",
    "org/jctools/queues/LinkedQueueNode.lvNext",
    "org/jctools/queues/atomic/LinkedQueueAtomicNode.lvNext",
)


private fun isFunctionCallAwait(insn: MethodInsnNode): Boolean =
    insn.opcode == Opcodes.INVOKESTATIC &&
    insn.owner == THREAD_TYPE.internalClassName &&
    insn.name == ON_SPIN_WAIT_METHOD_NAME &&
    insn.desc == ON_SPIN_WAIT_METHOD_DESCRIPTOR

private fun isSideEffectGetMethod(insn: MethodInsnNode): Boolean =
    "${insn.owner}.${insn.name}" in ATOMIC_SIDE_EFFECT_FREE_GET_METHODS

private fun isSideEffectFreeCall(insn: MethodInsnNode): Boolean =
    isFunctionCallAwait(insn) ||
    isSideEffectGetMethod(insn) ||
    isSafeMethodCall(insn.owner, insn.name, insn.desc, insn.opcode)

/**
 * Classification object result used for await path analysis.
 */
private data class BlockClassification(
    val hasSharedRead: Boolean,
    val hasSideEffects: Boolean,
) {
    fun merge(other: BlockClassification): BlockClassification =
        BlockClassification(
            hasSharedRead = hasSharedRead || other.hasSharedRead,
            hasSideEffects = hasSideEffects || other.hasSideEffects,
        )
}

private data class BfsEntry(val block: BasicBlockIndex, val classification: BlockClassification)

private fun MutableMap<BasicBlockIndex, BlockClassification>.enqueueIfChanged(
    block: BasicBlockIndex,
    classification: BlockClassification,
    queue: ArrayDeque<BfsEntry>,
) {
    val previousClassification = this[block]
    val merged = previousClassification?.merge(classification) ?: classification
    val hasChanged = merged != previousClassification
    if (!hasChanged) return
    this[block] = merged
    queue.add(BfsEntry(block, merged))
}

/**
 * Computes the set of "clean" back-edge source blocks for each loop containing await paths.
 *
 * Returns a map between [LoopId] and the set of clean back-edge source [BasicBlockIndex] values.
 */
internal fun BasicBlockControlFlowGraph.computeAwaitPathBackEdgeSources(
    loopInfo: MethodLoopsInformation
): Map<LoopId, Set<BasicBlockIndex>> {
    if (!loopInfo.hasLoops()) return emptyMap()

    // classify every basic block in the method
    val blockClassifications = Array(basicBlocks.size) { blockIndex ->
        val execRange = basicBlocks.getOrNull(blockIndex)?.executableRange
        if (execRange == null) {
            BlockClassification(hasSharedRead = false, hasSideEffects = false)
        } else {
            var hasSharedRead = false
            var hasSideEffects = false
            for (i in execRange) {
                val insn = instructions.get(i)
                val opcode = insn.opcode
                if (opcode < 0) continue
                if (isReadOpcode(opcode)) hasSharedRead = true
                if (isWriteOpcode(opcode) || isMonitorOpcode(opcode)) {
                    hasSideEffects = true
                    continue
                }
                when (insn) {
                    is MethodInsnNode -> {
                        if (!isSideEffectFreeCall(insn)) {
                            hasSideEffects = true
                            continue
                        }
                        if (isSideEffectGetMethod(insn)) {
                            hasSharedRead = true
                        }
                    }
                    is InvokeDynamicInsnNode -> {
                        hasSideEffects = true
                    }
                }
            }
            BlockClassification(hasSharedRead, hasSideEffects)
        }
    }

    val loopInitialClassifications = computeLoopInitialClassifications(loopInfo, blockClassifications)

    // for each loop, find if there exists clean back-edges.
    val result = mutableMapOf<LoopId, Set<BasicBlockIndex>>()

    for (loop in loopInfo.loops) {
        val awaitPathBackEdges = findCleanBackEdge(loop, blockClassifications, loopInitialClassifications.getValue(loop.id))
        if (awaitPathBackEdges.isNotEmpty()) {
            result[loop.id] = awaitPathBackEdges
        }
    }

    return result
}

/**
 * Computes the initial classification for each loop header.
 * Nested loops inherit side effects and reads accumulated on paths
 * from the header of the outer loop.
 */
private fun BasicBlockControlFlowGraph.computeLoopInitialClassifications(
    loopInfo: MethodLoopsInformation,
    blockClassifications: Array<BlockClassification>,
): Map<LoopId, BlockClassification> {
    val initialClassifications = mutableMapOf<LoopId, BlockClassification>()
    for (loop in loopInfo.loops) {
        val parent = loopInfo.loops
            .asSequence()
            .filter { candidate -> candidate.id != loop.id && candidate.body.containsAll(loop.body) }
            .minByOrNull { it.body.size }
        initialClassifications[loop.id] = if (parent == null) {
            blockClassifications[loop.header]
        } else {
            computeNestedLoopEntryClassification(
                parentLoop = parent,
                nestedLoop = loop,
                parentInitialClassification = initialClassifications.getValue(parent.id),
                blockClassifications = blockClassifications,
            )
        }
    }
    return initialClassifications
}

/**
 * Computes the merged classification of all paths from a parent loop header to a nested loop header.
 */
private fun BasicBlockControlFlowGraph.computeNestedLoopEntryClassification(
    parentLoop: LoopInformation,
    nestedLoop: LoopInformation,
    parentInitialClassification: BlockClassification,
    blockClassifications: Array<BlockClassification>,
): BlockClassification {
    val queue = ArrayDeque<BfsEntry>()
    val pathClassifications = mutableMapOf<BasicBlockIndex, BlockClassification>()
    pathClassifications.enqueueIfChanged(parentLoop.header, parentInitialClassification, queue)

    while (queue.isNotEmpty()) {
        val (currentBlock, pathClassification) = queue.removeFirst()
        if (currentBlock == nestedLoop.header) continue

        val successorEdges = allSuccessors[currentBlock] ?: continue
        for (edge in successorEdges) {
            val target = edge.target
            if (edge.label is EdgeLabel.Exception || target !in parentLoop.body || target in parentLoop.headers) continue

            val targetPathClassification = pathClassification.merge(blockClassifications[target])
            pathClassifications.enqueueIfChanged(target, targetPathClassification, queue)
        }
    }

    return pathClassifications[nestedLoop.header] ?: blockClassifications[nestedLoop.header]
}

/**
 * Finds clean back-edge source blocks by performing a forward BFS from the loop header.
 */
private fun BasicBlockControlFlowGraph.findCleanBackEdge(
    loop: LoopInformation,
    blockClassifications: Array<BlockClassification>,
    initialClassification: BlockClassification,
): Set<BasicBlockIndex> {
    val header = loop.header
    val bodyBlocks = loop.body
    val backEdgeSources = loop.backEdges.map { it.source }.toSet()

    // If the header or outer loop already have side effects, no await path can start from this header.
    if (initialClassification.hasSideEffects) return emptySet()

    val localVariablesInHeader = mutableSetOf<Int>()
    // Check for side effects on the header block and for variables loaded in the header and written in the body.
    val headerBlock = basicBlocks.getOrNull(header)
    if (headerBlock?.executableRange != null) {
        for (i in headerBlock.executableRange) {
            val insn = instructions.get(i)
            if (insn is VarInsnNode && isLoadOpcode(insn.opcode)) {
                localVariablesInHeader.add(insn.`var`)
            }
        }
    }

    val queue = ArrayDeque<BfsEntry>()
    val pathClassifications = mutableMapOf<BasicBlockIndex, BlockClassification>()

    pathClassifications.enqueueIfChanged(header, initialClassification, queue)

    while (queue.isNotEmpty()) {
        val (currentBlock, pathClassification) = queue.removeFirst()

        // Explore successors
        val successorEdges = allSuccessors[currentBlock] ?: continue
        for (edge in successorEdges) {
            val target = edge.target
            // Skip exception edges, targets outside the loop body, and back-edges to the header
            if (edge.label is EdgeLabel.Exception || target !in bodyBlocks || target in loop.headers) continue

            val targetClassification = blockClassifications[target]
            val targetPathClassification = BlockClassification(
                hasSharedRead = pathClassification.hasSharedRead || targetClassification.hasSharedRead,
                hasSideEffects = pathClassification.hasSideEffects ||
                    targetClassification.hasSideEffects ||
                    headerGuard(target, localVariablesInHeader),
            )
            pathClassifications.enqueueIfChanged(target, targetPathClassification, queue)
        }
    }

    val cleanBackEdges = mutableSetOf<BasicBlockIndex>()
    for (source in backEdgeSources) {
        val classification = pathClassifications[source] ?: continue
        if (classification.hasSharedRead && !classification.hasSideEffects) {
            cleanBackEdges.add(source)
        }
    }
    return cleanBackEdges
}

/**
 * Checks whether a block writes to a local variable loaded by the loop header, which can make a path to not be await-clean.
 */
private fun BasicBlockControlFlowGraph.headerGuard(
    blockIndex: BasicBlockIndex,
    localVariables: Set<Int>,
): Boolean {
    if (localVariables.isEmpty()) return false
    val block = basicBlocks.getOrNull(blockIndex) ?: return false
    val range = block.executableRange ?: return false
    for (i in range) {
        val insn = instructions.get(i)
        when (insn) {
            is VarInsnNode -> {
                if (isStoreOpcode(insn.opcode) && insn.`var` in localVariables) return true
            }
            is IincInsnNode -> {
                if (insn.`var` in localVariables) return true
            }
        }
    }
    return false
}
