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
import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.*
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.util.*
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type
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

    private val awaitLoopBackEdgeSources: Map<LoopId, Set<BasicBlockIndex>> =
        methodInfo.basicControlFlowGraph!!.computeAwaitLoops(loopInfo)

    private val awaitLoopIds: Set<LoopId> = awaitLoopBackEdgeSources.keys

    private val loopIdsByHeaderNonPhonyIndex: Map<InstructionIndex, List<LoopId>> =
        methodInfo.basicControlFlowGraph!!.computeLoopIdsByHeaderNonPhonyIndex(insnIndexRemapping, loopInfo)

    private val codeLocationIdByLoopId = mutableMapOf<LoopId, Int>()

    // Map from a non-phony instruction index (the last opcode of a clean back-edge source block)
    // to the loopId. These are the sites where `onAwaitLoop` should be injected.
    private val awaitInjectionLocation: Map<InstructionIndex, LoopId> =
        methodInfo.basicControlFlowGraph!!.computeAwaitInjectionLocation(insnIndexRemapping, awaitLoopBackEdgeSources)

    override fun beforeInsn(index: Int, opcode: Int): Unit = adapter.run {
        val nonPhonyIndex = currentNonPhonyInsnIndex

        loopIdsByHeaderNonPhonyIndex[nonPhonyIndex]?.let { loopIds ->
            val canonicalId = createAndDiscardCodeLocationId()
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
                loadNewCodeLocationId()
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
                if (loopId !in awaitLoopIds) {
                    adapter.invokeStatic(Injections::onLoopIteration)
                } else {
                    adapter.invokeStatic(Injections::onAwaitLoop)
                }
            } else if (shouldTrackIrreducibleLoops) {
                adapter.invokeStatic(Injections::onIrreducibleLoopIteration)
            }
            // STACK: <empty>
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
                loadNewCodeLocationId()
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
 * Compute injection location for await loops
 * This should happen at the back edge of the loop when the loop can be considered await.
 * by back edge we mean the jump instruction that goes back to the loop header.
 *
 * Returns a map from non-phony instruction index to the loop id.
 */
private fun BasicBlockControlFlowGraph.computeAwaitInjectionLocation(
    insnIndexRemapping: IntArray,
    awaitBackEdges: Map<LoopId, Set<BasicBlockIndex>>,
): Map<InstructionIndex, LoopId> {
    if (awaitBackEdges.isEmpty()) return emptyMap()
    val result = mutableMapOf<InstructionIndex, LoopId>()
    for ((loopId, sourceBlocks) in awaitBackEdges) {
        for (block in sourceBlocks) {
            // Inject at the last opcode of the source block
            val idx = lastOpcodeIndexOf(block) ?: continue
            val nonPhonyIndex = insnIndexRemapping[idx]
            if (nonPhonyIndex >= 0) {
                // In the case of nested loops when they share the injection location,
                // prefer the inner loop
                result.merge(nonPhonyIndex, loopId) { old, new -> maxOf(old, new) }
            }
        }
    }
    return result
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
 * A loop is considered an await loop if there exists at least a back edge path such that:
 *   - no shared writes (field/array writes), monitor operations, or side-effecting calls (except `Thread.onSpinWait`) are present on that path
 *   - at least one shared read (field/array read) is present on that path
 *
 * By this definition, a loop that contains writes or calls on some paths can still be classified as an await loop,
 * as long as it has at least one clean back-edge path that satisfies above conditions
 *
 * Examples:
 * ```
 * // Simple busy-wait — the whole loop body is read-only
 * while (!flag) { }
 *
 * // Complex loop with CAS and writes on some paths, but a read-only spin-retry path
 * _state.loop { state ->
 *     val element = array[head].value
 *     if (element == null) return@loop   // <-- read-only spin path
 *     if (_state.compareAndSet(old, new)) { ... return result }
 * }
 * ```
 */

private val ON_SPIN_WAIT_METHOD = runCatching {
    Thread::class.java.getDeclaredMethod("onSpinWait")
}.getOrNull()
private val THREAD_OWNER: String = Type.getInternalName(Thread::class.java)

/**
 * Classification object result used for await loop analysis
 */
private data class BlockClassification(
    val hasSharedRead: Boolean,
    val hasSideEffects: Boolean,
)

/**
 * Computes the set of "clean" back-edge source blocks for each await loop.
 *
 * Returns a map between [LoopId] and he set of clean back-edge source [BasicBlockIndex] values.
 */
internal fun BasicBlockControlFlowGraph.computeAwaitLoops(
    loopInfo: MethodLoopsInformation
): Map<LoopId, Set<BasicBlockIndex>> {
    if (!loopInfo.hasLoops()) return emptyMap()

    // helpers for classification
    fun isSharedWriteOpcode(opcode: Int): Boolean = when (opcode) {
        Opcodes.PUTFIELD, Opcodes.PUTSTATIC,
        Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE,
        Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE,
        Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> true
        else -> false
    }

    fun isSharedReadOpcode(opcode: Int): Boolean = when (opcode) {
        Opcodes.GETFIELD, Opcodes.GETSTATIC,
        Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD,
        Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> true
        else -> false
    }

    // TODO: Need to check for other functions as well + if the owner is right.
    fun isFunctionCallAwait(insn: MethodInsnNode): Boolean =
        insn.opcode == Opcodes.INVOKESTATIC &&
                insn.owner == THREAD_OWNER &&
                insn.name == ON_SPIN_WAIT_METHOD?.name &&
                insn.desc == ON_SPIN_WAIT_METHOD.let(Type::getMethodDescriptor)

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
                if (isSharedReadOpcode(opcode)) hasSharedRead = true
                if (isSharedWriteOpcode(opcode)) {
                    hasSideEffects = true
                    break
                }
                when (insn) {
                    is MethodInsnNode -> {
                        if (!isFunctionCallAwait(insn)) {
                            hasSideEffects = true
                            break
                        }
                    }
                    is InvokeDynamicInsnNode -> {
                        hasSideEffects = true
                        break
                    }
                }
            }
            BlockClassification(hasSharedRead, hasSideEffects)
        }
    }

    // for each loop, find if there exists clean back-edges.
    val result = mutableMapOf<LoopId, Set<BasicBlockIndex>>()

    for (loop in loopInfo.loops) {
        val awaitBackEdges = findCleanBackEdge(loop, blockClassifications)
        if (awaitBackEdges.isNotEmpty()) {
            result[loop.id] = awaitBackEdges
        }
    }

    return result
}

/**
 * Finds clean back-edge source blocks by performing a forward BFS from the loop header.
 */
private fun BasicBlockControlFlowGraph.findCleanBackEdge(
    loop: LoopInformation,
    blockClassifications: Array<BlockClassification>,
): Set<BasicBlockIndex> {
    val header = loop.header
    val bodyBlocks = loop.body
    val backEdgeSources = loop.backEdges.map { it.source }.toSet()

    val headerClassification = blockClassifications[header]

    // If the header block itself has side effects, no suitable await loop can be formed
    if (headerClassification.hasSideEffects) return emptySet()

    val localVariablesInHeader = mutableSetOf<Int>()
    // Check for side effects on the header block and for variables loaded in the header and written in the body.
    val headerBlock = basicBlocks.getOrNull(header)
    if (headerBlock?.executableRange != null) {
        for (i in headerBlock.executableRange) {
            val insn = instructions.get(i)
            if (insn is VarInsnNode &&
                insn.opcode in listOf(Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD)
            ) {
                localVariablesInHeader.add(insn.`var`)
            }
        }
    }

    fun headerGuard(blockIndex: BasicBlockIndex): Boolean {
        if (localVariablesInHeader.isEmpty()) return false
        val block = basicBlocks.getOrNull(blockIndex) ?: return false
        val range = block.executableRange ?: return false
        for (i in range) {
            val insn = instructions.get(i)
            when (insn) {
                is VarInsnNode -> {
                    if (insn.opcode in listOf(
                            Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE
                        ) && insn.`var` in localVariablesInHeader
                    ) return true
                }
                is IincInsnNode -> {
                    if (insn.`var` in localVariablesInHeader) return true
                }
            }
        }
        return false
    }

    // BFS visited state bitmask
    // VISITED_NO_READ = visited without a read on the path,
    // VISITED_WITH_READ = visited with at least one read on the path.
    val VISITED_NO_READ = 1
    val VISITED_WITH_READ = 2
    val visitedState = IntArray(basicBlocks.size)

    data class BfsEntry(val block: BasicBlockIndex, val hasRead: Boolean)

    val initialHasRead = headerClassification.hasSharedRead
    val queue = ArrayDeque<BfsEntry>()
    queue.add(BfsEntry(header, initialHasRead))
    visitedState[header] = if (initialHasRead) VISITED_WITH_READ else VISITED_NO_READ

    val cleanBackEdges = mutableSetOf<BasicBlockIndex>()

    while (queue.isNotEmpty()) {
        val (currentBlock, pathHasRead) = queue.removeFirst()

        // Add current block if is a back-edge source that has a read
        if (currentBlock in backEdgeSources && pathHasRead) {
            cleanBackEdges.add(currentBlock)
        }

        // Explore successors
        val successorEdges = allSuccessors[currentBlock] ?: continue
        for (edge in successorEdges) {
            val target = edge.target
            // Skip exception edges, targets outside the loop body, and back-edges to the header
            if (edge.label is EdgeLabel.Exception || target !in bodyBlocks || target in loop.headers) continue

            val targetClassification = blockClassifications[target]
            // Skip side effects
            if (targetClassification.hasSideEffects) continue
            if (headerGuard(target)) continue

            val newHasRead = pathHasRead || targetClassification.hasSharedRead
            val bfsBit = if (newHasRead) VISITED_WITH_READ else VISITED_NO_READ

            // Skip if this block was already visited with the same read state
            if ((visitedState[target] and bfsBit) == 0) {
                visitedState[target] = visitedState[target] or bfsBit
                queue.add(BfsEntry(target, newHasRead))
            }
        }
    }

    return cleanBackEdges
}
