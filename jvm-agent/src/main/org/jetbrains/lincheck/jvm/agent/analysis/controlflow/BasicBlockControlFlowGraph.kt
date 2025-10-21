/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.analysis.controlflow

import org.jetbrains.lincheck.util.*
import org.objectweb.asm.Label
import org.objectweb.asm.tree.*
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceMethodVisitor
import java.io.PrintWriter
import java.io.StringWriter

/**
 * A type alias representing the index of a basic block within a control flow graph.
 */
typealias BasicBlockIndex = Int

/**
 * Class representing a basic block within a control-flow graph.
 *
 * A basic block is a contiguous, non-branching sequence of instructions
 * with a single entry point and a single exit point (branch).
 *
 * @property index Unique index of the basic block within its control-flow graph.
 *
 * @property range Range of instruction indices which make up the basic block.
 *
 * @property executableRange Range of real opcode instruction indices within this block,
 *   excluding labels/frames/lines instructions.
 *   Null if the block is empty (e.g., only labels/frames/lines).
 *
 * @property entryLabelIndex Index of the first Label within this block's range (if any).
 */
data class BasicBlock(
    val index: Int,
    val range: InstructionsRange,
    val executableRange: InstructionsRange?,
    val entryLabelIndex: InstructionIndex?,
) {
    init {
        if (executableRange != null) {
            require(!executableRange.isEmpty()) {
                "Executable range of a basic block should not be empty"
            }
            require(range.first <= executableRange.first && executableRange.last <= range.last) {
                "Executable range should be a subrange of the basic block's range"
            }
        }
        if (entryLabelIndex != null) {
            require(range.first <= entryLabelIndex && entryLabelIndex <= range.last) {
                "Entry label index should be within the basic block's range"
            }
        }
    }
}

typealias InstructionsRange = IntRange

/**
 * A control-flow graph on the level of basic blocks.
 */
class BasicBlockControlFlowGraph(
    val instructions: InsnList,
    val basicBlocks: List<BasicBlock>,
) : ControlFlowGraph() {

    init {
        validateStructure()
        validateEdgeInvariants()
    }

    /**
     * Information about loops in this method.
     */
    var loopInfo: MethodLoopsInformation? = null
        private set

    /** Returns the first executable opcode index of the given block, or null if none. */
    fun firstOpcodeIndexOf(block: BasicBlockIndex): InstructionIndex? =
        basicBlocks.getOrNull(block)?.executableRange?.first

    /** Returns the last executable opcode index of the given block, or null if none. */
    fun lastOpcodeIndexOf(block: BasicBlockIndex): InstructionIndex? =
        basicBlocks.getOrNull(block)?.executableRange?.last

    /** Returns the first Label object of the given block, or null if none. */
    fun firstLabelOf(block: BasicBlockIndex): Label? {
        val idx = basicBlocks.getOrNull(block)?.entryLabelIndex ?: return null
        val node = instructions.get(idx)
        return (node as? LabelNode)?.label
    }

    /**
     * Computes loop-related information for this method.
     */
    fun computeLoopInformation(): MethodLoopsInformation {
        if (loopInfo == null) {
            // compute predecessors for all basic blocks
            val allPredecessors: Array<MutableSet<BasicBlockIndex>> = Array(basicBlocks.size) { mutableSetOf() }
            val normalPredecessors: Array<MutableSet<BasicBlockIndex>> = Array(basicBlocks.size) { mutableSetOf() }
            for (e in edges) {
                val u = e.source
                val v = e.target
                allPredecessors[v].add(u)
                if (e.label !is EdgeLabel.Exception) {
                    normalPredecessors[v].add(u)
                }
            }
            // compute loop information
            val dominators = computeDominators(allPredecessors)
            loopInfo = computeLoopsFromDominators(dominators, normalPredecessors).also { info ->
                info.validateBasicBlocksLoopsMapping()
                info.loops.forEach {
                    it.validateLoopEdgesInvariants()
                }
            }
        }
        return loopInfo!!
    }

    /**
     * Validates the structure of this basic-block graph.
     */
    fun validateStructure() {
        require(basicBlocks.allIndexed { index, block -> block.index == index }) {
            "Basic blocks indices should match their positions in the list"
        }
        require(basicBlocks.isSortedBy { it.range.first }) {
            "Basic blocks should be sorted by their ranges in ascending order"
        }
        require(basicBlocks.isSortedWith { b1, b2 -> Integer.compare(b1.range.last, b2.range.first) }) {
            "Basic blocks should be sorted by their ranges in ascending order"
        }
    }

    /**
     * Validates edge invariants for this basic-block graph.
     *
     * - For Jump edges: the recorded instruction must be the last real opcode of the source block.
     * - For FallThrough and Exception edges: no invariants are checked at the basic block level.
     */
    fun validateEdgeInvariants() {
        for (e in edges) {
            when (val label = e.label) {
                is EdgeLabel.Jump -> {
                    val last = lastOpcodeIndexOf(e.source)
                    val jumpIdx = instructions.indexOf(label.instruction)
                    require(last != null && last == jumpIdx) {
                        """
                            Jump edge must be produced by the last opcode of the source block: 
                            source=B${e.source}, target=B${e.target}, last=${last}, jumpIdx=${jumpIdx}
                        """.trimIndent()
                    }
                }
                // no invariants here at BB layer
                is EdgeLabel.FallThrough -> {}
                // no invariants here at BB layer
                is EdgeLabel.Exception -> {}
            }
        }
    }

    /**
     * Validates loop's edges invariants for the current loop.
     */
    private fun LoopInformation.validateLoopEdgesInvariants() {
        for (e in normalExits) {
            val last = lastOpcodeIndexOf(e.source) ?: continue
            val lastInstruction = instructions.get(last)
            require(isRecognizedIfJumpOpcode(lastInstruction.opcode)) {
                """
                    Normal loop exit fall-through edge must be produced by an IF* opcode at the end of the source block: 
                    source=B${e.source}, target=B${e.target}, opcode=${lastInstruction.opcode}
                """.trimIndent()
            }
         }
    }

    /**
     * Validates that every basic block is contained inside each loop it is mapped to.
     */
    private fun MethodLoopsInformation.validateBasicBlocksLoopsMapping() {
        if (loopsByBlock.isEmpty()) return
        for ((block, loops) in loopsByBlock) {
            for (loopId in loops) {
                val loop = getLoopInfo(loopId)
                require(loop != null) { "Loop $loopId is not found" }
                require(block in loop.body) { "Block B$block is not found in loop $loop, but is mapped to it" }
            }
        }
    }
}

/**
 * Builds a basic-block level CFG from the given instruction-level CFG.
 */
fun InstructionControlFlowGraph.toBasicBlockGraph(): BasicBlockControlFlowGraph {
    // Identify leaders (first instructions of basic blocks).
    //
    // An instruction is a leader if it is:
    //   1. the first instruction of the program;
    //   2. any instruction that is the target of a conditional or unconditional jump;
    //   3. instruction immediately following a conditional if jump (since control may fall through);
    //   4. any instruction that is the target of exception handler jump.
    val leaders =
        buildSet {
            add(0)
            edgeMap.values.forEach { edges ->
                edges.forEach { edge ->
                    if (edge.label is EdgeLabel.Jump) {
                        add(edge.target)
                        if (edge.label.isIfConditional && hasEdge(edge.source, edge.source + 1)) {
                            add(edge.source + 1)
                        }
                    }
                    if (edge.label is EdgeLabel.Exception) {
                        add(edge.target)
                    }
                }
            }
        }
        .toMutableList()
        .sorted()

    val maxInstructionIndex = nodes.maxOrNull() ?: 0

    // Build basic blocks as contiguous ranges between leaders.
    // For each leader, take all instructions until the next leader or the last instruction.
    val basicBlocks = mutableListOf<BasicBlock>()
    leaders.forEachIndexed { i, leader ->
        val endExclusive = leaders.getOrElse(i + 1) { maxInstructionIndex + 1 }
        val range = (leader until endExclusive)
        // Compute range of real opcodes inside the block (skip labels/frames/lines)
        var firstOpcode: Int? = null
        var lastOpcode: Int? = null
        for (idx in range) {
            val n = instructions.get(idx)
            if (n.opcode >= 0) { firstOpcode = idx; break }
        }
        for (idx in range.last downTo range.first) {
            val n = instructions.get(idx)
            if (n.opcode >= 0) { lastOpcode = idx; break }
        }
        val executableRange = if (firstOpcode != null && lastOpcode != null) (firstOpcode..lastOpcode) else null
        // Compute the first label inside the block, if any (store its InsnList index)
        var firstLabelIndex: Int? = null
        for (idx in range) {
            val n = instructions.get(idx)
            if (n is LabelNode) { firstLabelIndex = idx; break }
        }
        basicBlocks += BasicBlock(i, range, executableRange, firstLabelIndex)
    }

    // Map: instruction index -> basic block index
    val instructionToBlock = mutableMapOf<InstructionIndex, BasicBlockIndex>()
    basicBlocks.forEach { block ->
        block.range.forEach { instructionIndex ->
            instructionToBlock[instructionIndex] = block.index
        }
    }
    
    // Project instruction edges onto basic blocks.
    val basicBlockGraph = BasicBlockControlFlowGraph(instructions, basicBlocks)
    for ((u, edges) in edgeMap) {
        val bu = instructionToBlock[u] ?: continue
        for (edge in edges) {
            val v = edge.target
            val bv = instructionToBlock[v] ?: continue
            // Suppress only intra-block fall-through edges (they don't create inter-block flow),
            // but keep intra-block self-loops for jumps (e.g., do-while back edges) and exceptions.
            if (bu == bv && edge.label is EdgeLabel.FallThrough) continue
            basicBlockGraph.addEdge(bu, bv, edge.label)
        }
    }
    // Validate edge invariants after projection
    basicBlockGraph.validateEdgeInvariants()

    return basicBlockGraph
}

fun BasicBlockControlFlowGraph.toFormattedString(): String =
    BasicBlockControlFlowGraphFormatter(this).toFormattedString()

private class BasicBlockControlFlowGraphFormatter(val graph: BasicBlockControlFlowGraph) {

    // use a single `Textifier` per printing session to ensure stable label names
    private val textifier = Textifier()

    fun toFormattedString(): String {
        val sb = StringBuilder()

        // Blocks section
        sb.appendLine("BLOCKS")
        val lastInsnIndex = (graph.instructions.size() - 1).takeIf { it >= 0 } ?: 0
        val insnIndexWidth = lastInsnIndex.toString().length
        for (block in graph.basicBlocks) {
            val first = block.range.firstOrNull()
            val last = block.range.lastOrNull()
            val range = when {
                first == null -> "[]"
                first == last -> "[$first]"
                else          -> "[$first..$last]"
            }
            sb.appendLine("B${block.index}: $range")

            // Instructions section
            for (insnIndex in block.range) {
                val insn = graph.instructions.get(insnIndex)
                val text = insn.toFormattedString()
                val idx = insnIndex.toString().padStart(insnIndexWidth, ' ')
                sb.appendLine("  $idx: $text")
            }
        }
        sb.appendLine()

        // Edges section
        sb.appendLine("EDGES")
        graph.edges.let {
            if (it.isNotEmpty()) sb.append(
                it.toFormattedString().prependIndent("  ")
            )
        }
        return sb.toString()
    }

    private fun AbstractInsnNode.toFormattedString(): String {
        // clears only the output buffer;
        // label mappings inside `textifier` remain intact
        textifier.text.clear()

        val visitor = TraceMethodVisitor(textifier)
        this.accept(visitor)

        val writer = StringWriter()
        val printer = PrintWriter(writer)
        textifier.print(printer)
        printer.flush()

        return writer.toString().trimEnd()
    }
}
