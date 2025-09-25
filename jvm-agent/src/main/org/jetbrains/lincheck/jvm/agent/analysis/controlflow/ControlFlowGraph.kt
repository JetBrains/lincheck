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
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode

/**
 * A type alias representing an index used for identifying nodes in a control flow graph.
 * Effectively, either an instruction index or a basic-block index.
 */
typealias NodeIndex = Int

/**
 * A type representing the label of a control-flow edge,
 * containing information about the type of the transition.
 */
sealed class EdgeLabel {
    /**
     * A normal fall-through from instruction i to i + 1.
     */
    data object FallThrough : EdgeLabel()

    /**
     * A jump transition produced by one of the JVM branch instructions (if/switch/goto).
     *
     * @property instruction the ASM instruction node which produced this edge.
     */
    data class Jump(val instruction: AbstractInsnNode) : EdgeLabel() {

        val opcode: Int get() = instruction.opcode

        init {
            require(isRecognizedJumpOpcode(opcode)) {
                "Unrecognized jump opcode: $opcode"
            }
        }

        /**
         * True if this jump is conditional.
         */
        val isConditional: Boolean get() = when (opcode) {
            Opcodes.GOTO, Opcodes.JSR -> false
            else -> true
        }
    }

    /**
     * An exception transition into a handler block.
     */
    data object Exception : EdgeLabel()
}

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

/**
 * Helper: checks if the given opcode corresponds to a recognized JVM jump/switch instruction.
 */
private fun isRecognizedJumpOpcode(opcode: Int): Boolean = when (opcode) {
    // Goto
    Opcodes.GOTO,
    // Single-operand zero comparisons
    Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
    // Single-operand null comparisons
    Opcodes.IFNULL, Opcodes.IFNONNULL,
    // Two-operand numeric comparisons
    Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
    // Two-operand object reference comparisons
    Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
    // Switches
    Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH,
         -> true

    Opcodes.JSR, Opcodes.RET -> {
        Logger.warn {
            "Deprecated JSR/RET instructions are encountered during control-flow graph construction."
        }
        true
    }

    else -> false
}