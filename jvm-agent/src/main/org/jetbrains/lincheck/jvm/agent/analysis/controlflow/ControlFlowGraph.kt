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

/**
 * A type alias representing an index used for identifying nodes in a control flow graph.
 * Effectively, either an instruction index or a basic-block index.
 */
typealias NodeIndex = Int

typealias EdgeMap = Map<NodeIndex, Set<NodeIndex>>
typealias MutableEdgeMap = MutableMap<NodeIndex, MutableSet<NodeIndex>>

/**
 * Base class for representing control-flow graph.
 *
 * This class models control-flow within a method, where:
 * - each node represents an instruction or basic block;
 * - edges denote possible transitions in method execution, either regular or exceptional.
 */
sealed class ControlFlowGraph {

    /**
     * Nodes (instruction or basic-block indices) of the control flow graph.
     */
    val nodes: Set<NodeIndex> get() = _nodes
    private val _nodes: MutableSet<NodeIndex> = mutableSetOf()

    /**
     * Regular (non-exceptional) control flow edges.
     */
    val edges: EdgeMap get() = _edges
    private val _edges: MutableEdgeMap = mutableMapOf()

    /**
     * Exceptional control flow edges.
     */
    val exceptionEdges: EdgeMap get() = _exceptionEdges
    private val _exceptionEdges: MutableEdgeMap = mutableMapOf()

    fun addEdge(src: NodeIndex, dst: NodeIndex) {
        _nodes.add(src)
        _nodes.add(dst)
        _edges.updateInplace(src, default = mutableSetOf()) { add(dst) }
    }

    fun addExceptionEdge(src: NodeIndex, dst: NodeIndex) {
        _nodes.add(src)
        _nodes.add(dst)
        _exceptionEdges.updateInplace(src, default = mutableSetOf()) { add(dst) }
    }
}