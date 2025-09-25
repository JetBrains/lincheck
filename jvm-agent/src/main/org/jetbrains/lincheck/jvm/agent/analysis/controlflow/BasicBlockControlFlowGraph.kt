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

/**
 * A type alias representing the index of a basic block within a control flow graph.
 */
typealias BasicBlockIndex = Int

/**
 * A control-flow graph on the level of basic blocks.
 */
class BasicBlockControlFlowGraph(val basicBlocks: List<BasicBlock>) : ControlFlowGraph() {}

/**
 * Builds a basic-block level CFG from the given instruction-level CFG.
 */
fun InstructionControlFlowGraph.toBasicBlockGraph(): BasicBlockControlFlowGraph {
    // TODO: this algorithm assumes out instruction-level CFG does not have trivial (i, i+1) edges;
    //   we need to adjust instruction-level CFG builder to satisfy this assumption.

    // Identify leaders (first instructions of basic blocks).
    //
    // An instruction is a leader if it is:
    //   1. the first instruction of the program;
    //   2. any instruction that is the target of a conditional or unconditional jump;
    //   3. instruction immediately following a conditional jump.
    val leaders = buildSet {
        add(0)
        edges.values.forEach { addAll(it) }
        exceptionEdges.values.forEach { addAll(it) }
    }.toMutableList().sorted()

    val maxInstructionIndex = nodes.maxOrNull() ?: 0

    // Build basic blocks as contiguous ranges between leaders.
    // For each leader, take all instructions until the next leader or the last instruction.
    val basicBlocks = mutableListOf<BasicBlock>()
    leaders.forEachIndexed { i, leader ->
        val endExclusive = leaders.getOrElse(i + 1) { maxInstructionIndex + 1 }
        val instructions = (leader until endExclusive).toList()
        basicBlocks += BasicBlock(index = i, instructions = instructions)
    }

    // Map: instruction index -> basic block index
    val instructionToBlock = mutableMapOf<InstructionIndex, BasicBlockIndex>()
    basicBlocks.forEach { block ->
        block.instructions.forEach { instructionIndex ->
            instructionToBlock[instructionIndex] = block.index
        }
    }
    
    // Project instruction edges onto basic blocks.
    val basicBlockGraph = BasicBlockControlFlowGraph(basicBlocks)
    for ((u, destinations) in edges) {
        val bu = instructionToBlock[u] ?: continue
        for (v in destinations) {
            val bv = instructionToBlock[v] ?: continue
            basicBlockGraph.addEdge(bu, bv)
        }
    }
    for ((u, destinations) in exceptionEdges) {
        val bu = instructionToBlock[u] ?: continue
        for (v in destinations) {
            val bv = instructionToBlock[v] ?: continue
            basicBlockGraph.addExceptionEdge(bu, bv)
        }
    }
    return basicBlockGraph
}
