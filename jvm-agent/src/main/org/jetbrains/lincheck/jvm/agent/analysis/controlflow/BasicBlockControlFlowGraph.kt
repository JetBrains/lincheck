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
import kotlin.collections.component1
import kotlin.collections.component2

/**
 * A type alias representing the index of a basic block within a control flow graph.
 */
typealias BasicBlockIndex = Int

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
    //   3. instruction immediately following a conditional jump (since control may fall through);
    //   4. any instruction that is the target of exception handler jump.
    val leaders =
        buildSet {
            add(0)
            edgeMap.values.forEach { edges ->
                edges.forEach { edge ->
                    if (edge.label is EdgeLabel.Jump) {
                        add(edge.target)
                        if (edge.label.isConditional && hasEdge(edge.source, edge.source + 1)) {
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
            basicBlockGraph.addEdge(bu, bv, edge.label)
        }
    }
    return basicBlockGraph
}

fun BasicBlockControlFlowGraph.prettyPrint(): String {
    val sb = StringBuilder()

    // Blocks section
    sb.appendLine("BLOCKS")
    for (block in basicBlocks.sortedBy { it.index }) {
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
            val insn = instructions.get(insnIndex)
            val text = insn.prettyPrint()
            sb.appendLine("  $insnIndex: $text")
        }
    }

    // Edges section
    sb.appendLine("EDGES")
    val edges: List<Edge> = edges.toMutableList().apply {
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
    is EdgeLabel.FallThrough    -> "0"
    is EdgeLabel.Jump           -> "1:${Printer.OPCODES[label.opcode]}"
    is EdgeLabel.Exception      -> "2:${label.caughtTypeName ?: "*"}"
}

private fun EdgeLabel.prettyPrint(): String = when (this) {
    is EdgeLabel.FallThrough    -> ""
    is EdgeLabel.Jump           -> "JUMP(opcode=${Printer.OPCODES[opcode]})"
    is EdgeLabel.Exception      -> "CATCH(type=${caughtTypeName ?: "*"})"
}

private fun AbstractInsnNode.prettyPrint(): String {
    val opcode = opcode
    if (opcode >= 0) {
        return Printer.OPCODES[opcode]
    }
    return when (this) {
        is LabelNode        -> "LABEL"
        is LineNumberNode   -> "LINE $line"
        is FrameNode        -> "FRAME"
        else                -> this.javaClass.simpleName
    }
}
