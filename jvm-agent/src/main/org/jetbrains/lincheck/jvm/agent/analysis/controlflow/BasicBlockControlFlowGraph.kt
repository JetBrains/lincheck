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
            val dominators = computeDominators()
            loopInfo = computeLoopsFromDominators(dominators).also { info ->
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

    /**
     * Compute dominator sets for each basic block using the classical iterative algorithm.
     * The entry block is assumed to be block 0 when the graph is non-empty.
     * Returns an array where index i holds the set of dominators of block i (including i).
     *
     * A node d dominates node n if every path from the entry node to n must go through d.
     * By definition, every node dominates itself. The entry node dominates all nodes in
     * a reducible control flow graph.
     */
    private fun computeDominators(): Array<Set<BasicBlockIndex>> {
        val n = basicBlocks.size
        if (n == 0) return emptyArray()

        // Build predecessor lists; include both normal and exception predecessors for loop analysis dominators.
        val preds: Array<MutableSet<BasicBlockIndex>> = Array(n) { mutableSetOf<BasicBlockIndex>() }
        for (e in edges) {
            val u = e.source
            val v = e.target
            if (u in 0 until n && v in 0 until n) {
                preds[v].add(u)
            }
        }

        val all: Set<BasicBlockIndex> = (0 until n).toSet()
        val doms: Array<Set<BasicBlockIndex>> = Array(n) { all.toMutableSet() }
        // Entry is block 0 when present
        doms[0] = setOf(0)

        var changed = true
        while (changed) {
            changed = false
            for (b in 1 until n) {
                // Intersection of dominators of predecessors: dom(b) = {b} + intersect(dom(p1), dom(p2), ...)
                val newSet = preds[b].map { doms[it] }.intersectAll().apply { add(b) }
                if (newSet != doms[b]) {
                    doms[b] = newSet
                    changed = true
                }
            }
        }
        return Array(n) { doms[it].toSet() }
    }

    /**
     * Compute loops using dominators and back-edge detection.
     * A back edge is an edge u -> h (non-exception) where h dominates u.
     * For each header h, the loop body is the union of natural loops of its back edges.
     *
     * Note: "natural loops" are loops calculated via the algorithm below.
     * If there is more than one back edge to the same header, the body of the loop is the union of the nodes computed for each back edge.
     * Since loops can nest, a header for one loop can be in the body of (but not the header of) another loop.
     * See https://pages.cs.wisc.edu/~fischer/cs701.f14/finding.loops.html
     */
    private fun computeLoopsFromDominators(dominators: Array<Set<BasicBlockIndex>>): MethodLoopsInformation {
        val n = basicBlocks.size
        if (n == 0) return MethodLoopsInformation()

        // Build predecessor maps (normal and all) and collect non-exception edges for exits and back-edges.
        // TODO: this info is shared between dominators and back-edges calculation, its calculation could united
        val predsNormal: Array<MutableSet<BasicBlockIndex>> = Array(n) { mutableSetOf() }
        val predsAll: Array<MutableSet<BasicBlockIndex>> = Array(n) { mutableSetOf() }
        val normalEdges = mutableSetOf<Edge>()
        for (e in edges) {
            val u = e.source
            val v = e.target
            if (u !in 0 until n || v !in 0 until n) continue
            predsAll[v].add(u)
            if (e.label !is EdgeLabel.Exception) {
                predsNormal[v].add(u)
                normalEdges.add(e)
            }
        }

        // Identify back edges grouped by header h
        val backEdgesByHeader = mutableMapOf<BasicBlockIndex, MutableSet<Edge>>()
        for (e in normalEdges) {
            val u = e.source
            val h = e.target
            val domU = dominators.getOrElse(u) { emptySet() }
            if (h in domU) {
                backEdgesByHeader.updateInplace(h, default = mutableSetOf()) { add(e) }
            }
        }
        if (backEdgesByHeader.isEmpty()) return MethodLoopsInformation()

        // For each header, compute loop body as a union of natural loops for each back edge to that header
        data class LoopDescriptor(
            val header: BasicBlockIndex,
            val body: MutableSet<BasicBlockIndex> = mutableSetOf(),
            val backEdges: MutableSet<Edge> = mutableSetOf(),
        )
        val loopsDescriptors = mutableListOf<LoopDescriptor>()

        for ((h, backEdges) in backEdgesByHeader.toSortedMap()) {
            val body = mutableSetOf<BasicBlockIndex>()
            body.add(h)
            // Start from each back edge source; perform reverse DFS over normal predecessors until reaching h
            for (e in backEdges) {
                val u = e.source
                val stack = ArrayDeque<BasicBlockIndex>()
                // Natural loop includes both u and h initially
                if (body.add(u)) stack.add(u)
                while (stack.isNotEmpty()) {
                    val x = stack.removeLast()
                    for (p in predsNormal[x]) {
                        if (body.add(p)) stack.add(p)
                    }
                }
            }
            loopsDescriptors += LoopDescriptor(h, body, backEdges)
        }

        // Build final LoopInformation list
        val loops = mutableListOf<LoopInformation>()
        loopsDescriptors.sortBy { it.header }
        for ((id, desc) in loopsDescriptors.withIndex()) {
            val header = desc.header
            val headers = setOf(header) // reducible by construction
            val body = desc.body

            // Normal exits: edges from body to outside, non-exception
            val normalExits = buildSet {
                for (e in normalEdges) {
                    if (e.source in body && e.target !in body) add(e)
                }
            }
            // Exceptional exit handlers: targets of exception edges leaving the body
            val exceptionalExitHandlers = buildSet {
                for (e in edges) {
                    if (e.label is EdgeLabel.Exception && e.source in body && e.target !in body) {
                        add(e.target)
                    }
                }
            }

            val loop = LoopInformation(
                id = id,
                header = header,
                headers = headers,
                body = body,
                backEdges = desc.backEdges,
                normalExits = normalExits,
                exceptionalExitHandlers = exceptionalExitHandlers,
            )
            loops += loop
        }

        // Map blocks to loop ids
        val loopsByBlock = mutableMapOf<BasicBlockIndex, MutableList<LoopId>>()
        for (loop in loops) {
            for (b in loop.body) {
                loopsByBlock.updateInplace(b, default = mutableListOf()) { add(loop.id) }
            }
        }
        return MethodLoopsInformation(loops = loops, loopsByBlock = loopsByBlock)
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
