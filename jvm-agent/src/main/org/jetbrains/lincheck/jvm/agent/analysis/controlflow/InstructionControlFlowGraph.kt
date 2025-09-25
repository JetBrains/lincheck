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

import org.jetbrains.lincheck.util.updateInplace
import org.objectweb.asm.tree.InsnList

typealias InstructionIndex = Int

typealias InstructionEdgeMap = Map<InstructionIndex, Set<InstructionIndex>>
typealias InstructionMutableEdgeMap = MutableMap<InstructionIndex, MutableSet<InstructionIndex>>

/**
 * Represents an instruction-level control flow graph for a method.
 *
 * Each node in the graph represents an instruction of the method, stored as an instruction index.
 * Each edge in the graph represents a control flow transition between two instructions.
 *
 * This graph distinguishes regular and exceptional control flow edges.
 */
class InstructionControlFlowGraph() {

    /**
     * Nodes (instruction indices) of the control flow graph.
     */
    val nodes: Set<InstructionIndex> get() = _nodes
    private val _nodes: MutableSet<InstructionIndex> = mutableSetOf()

    /**
     * Regular (non-exceptional) control flow edges.
     */
    val edges: InstructionEdgeMap get() = _edges
    private val _edges: InstructionMutableEdgeMap = mutableMapOf()

    /**
     * Exceptional control flow edges.
     */
    val exceptionEdges: InstructionEdgeMap get() = _exceptionEdges
    private val _exceptionEdges: InstructionMutableEdgeMap = mutableMapOf()

    fun addEdge(src: InstructionIndex, dst: InstructionIndex) {
        _nodes.add(src)
        _nodes.add(dst)
        _edges.updateInplace(src, default = mutableSetOf()) { add(dst) }
    }

    fun addExceptionEdge(src: InstructionIndex, dst: InstructionIndex) {
        _nodes.add(src)
        _nodes.add(dst)
        _exceptionEdges.updateInplace(src, default = mutableSetOf()) { add(dst) }
    }
}