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

import org.objectweb.asm.tree.*
import org.objectweb.asm.util.Printer
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
 * @property range Range of instruction indices which make up the basic block.
 */
data class BasicBlock(
    val index: Int,
    val range: InstructionsRange,
)

typealias InstructionsRange = IntRange

/**
 * A control-flow graph on the level of basic blocks.
 */
class BasicBlockControlFlowGraph(
    val instructions: InsnList,
    val basicBlocks: List<BasicBlock>,
) : ControlFlowGraph()

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
        val instructions = (leader until endExclusive)
        basicBlocks += BasicBlock(i, instructions)
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
    return basicBlockGraph
}

fun BasicBlockControlFlowGraph.prettyPrint(): String =
    BasicBlockControlFlowGraphPrinter(this).prettyPrint()

private class BasicBlockControlFlowGraphPrinter(val graph: BasicBlockControlFlowGraph) {

    // use a single `Textifier` per printing session to ensure stable label names
    private val textifier = Textifier()

    fun prettyPrint(): String {
        val sb = StringBuilder()

        // Blocks section
        sb.appendLine("BLOCKS")
        val lastInsnIndex = (graph.instructions.size() - 1).takeIf { it >= 0 } ?: 0
        val insnIndexWidth = lastInsnIndex.toString().length
        for (block in graph.basicBlocks.sortedBy { it.index }) {
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
                val text = insn.prettyPrint()
                val idx = insnIndex.toString().padStart(insnIndexWidth, ' ')
                sb.appendLine("  $idx: $text")
            }
        }
        sb.appendLine()

        // Edges section
        sb.appendLine("EDGES")
        val edges: List<Edge> = graph.edges.toMutableList().apply {
            sortWith(compareBy({ it.source }, { it.target }, { labelSortKey(it.label) }))
        }
        for (e in edges) {
            val label = e.label.prettyPrint().takeIf { it.isNotEmpty() }
            sb.append("  B${e.source} -> B${e.target}")
            sb.append(label?.let { " : $it" }.orEmpty())
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun labelSortKey(label: EdgeLabel): String = when (label) {
        is EdgeLabel.FallThrough -> "0"
        is EdgeLabel.Jump        -> "1:${Printer.OPCODES[label.opcode]}"
        is EdgeLabel.Exception   -> "2:${label.caughtTypeName ?: "*"}"
    }

    private fun EdgeLabel.prettyPrint(): String = when (this) {
        is EdgeLabel.FallThrough -> ""
        is EdgeLabel.Jump        -> "JUMP(opcode=${Printer.OPCODES[opcode]})"
        is EdgeLabel.Exception   -> "CATCH(type=${caughtTypeName ?: "*"})"
    }

    private fun AbstractInsnNode.prettyPrint(): String {
        // clears only the output buffer; label mappings inside `textifier` remain intact
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
