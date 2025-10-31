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

import org.jetbrains.lincheck.descriptors.MethodSignature
import org.jetbrains.lincheck.util.*
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.TryCatchBlockNode
import org.objectweb.asm.util.Printer

/**
 * A type alias representing an index used for identifying nodes in a control flow graph.
 * Effectively, either an instruction index or a basic-block index.
 */
typealias NodeIndex = Int

/**
 * A type representing the label of a control-flow edge,
 * containing information about the type of the transition.
 */
sealed class EdgeLabel : Comparable<EdgeLabel> {

    override fun compareTo(other: EdgeLabel): Int {
        fun labelSortKey(label: EdgeLabel): String = when (label) {
            is FallThrough -> "0"
            is Jump        -> "1:${Printer.OPCODES[label.opcode]}"
            is Exception   -> "2:${label.caughtTypeName ?: "*"}"
        }

        val sk1 = labelSortKey(this)
        val sk2 = labelSortKey(other)
        return sk1.compareTo(sk2)
    }

    /**
     * A normal fall-through from instruction i to i + 1.
     */
    object FallThrough : EdgeLabel()

    /**
     * A jump transition produced by one of the JVM branch instructions (if/switch/goto).
     *
     * @property instruction the ASM instruction node which produced this edge.
     * @property opcode the opcode of the jump instruction.
     * @property isConditional true if this jump is conditional.
     */
    class Jump(val instruction: AbstractInsnNode) : EdgeLabel() {
        init {
            require(isRecognizedJumpOpcode(opcode)) {
                "Unrecognized jump opcode: $opcode"
            }
        }

        val opcode: Int get() = instruction.opcode

        val isConditional: Boolean get() = when (opcode) {
            Opcodes.GOTO, Opcodes.JSR -> false
            else -> true
        }

        val isIfConditional: Boolean get() = when (opcode) {
            Opcodes.GOTO, Opcodes.JSR, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> false
            else -> true
        }

        override fun toString(): String = "JUMP(opcode=${Printer.OPCODES[opcode]})"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Jump) return false
            // only opcode matters for label semantics
            return this.opcode == other.opcode
        }

        override fun hashCode(): Int = opcode
    }

    /**
     * An exception transition into a handler block.
     *
     * @property tryCatchBlock optional ASM try/catch block node which defined this handler edge.
     * @property caughtTypeName the internal name of the caught exception type (e.g., "java/lang/Exception"),
     *   or null for a catch-all (finally-like) handler.
     * @property isCatchAll true if this handler is a catch-all (finally-like) handler.
     */
    class Exception(
        val tryCatchBlock: TryCatchBlockNode? = null,
    ) : EdgeLabel() {
        val caughtTypeName: String? get() = tryCatchBlock?.type

        val isCatchAll: Boolean get() = caughtTypeName == null

        override fun toString(): String = "CATCH(type=${caughtTypeName ?: "*"})"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Exception) return false
            // only the caught exception type matters (null means catch-all)
            return this.caughtTypeName == other.caughtTypeName
        }

        override fun hashCode(): Int = caughtTypeName?.hashCode() ?: 0
    }
}

class Edge(
    val source: NodeIndex,
    val target: NodeIndex,
    val label: EdgeLabel,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Edge) return false
        return source == other.source &&
               target == other.target &&
                label == other.label
    }

    override fun hashCode(): Int {
        var result = 0
        result = 31 * result + source.hashCode()
        result = 31 * result + target.hashCode()
        result = 31 * result + label.hashCode()
        return result
    }

    operator fun component1() = source
    operator fun component2() = target
    operator fun component3() = label
}

typealias AdjacencyMap = Map<NodeIndex, Set<Edge>>
typealias MutableEdgeMap = MutableMap<NodeIndex, MutableSet<Edge>>

fun AdjacencyMap.neighbours(idx: NodeIndex): Iterable<NodeIndex> = (this[idx]?.map { it.target } ?: emptySet()).asIterable()

/**
 * Base class for representing control-flow graph.
 *
 * This class models control-flow within a method, where:
 * - each node represents an instruction or basic block;
 * - edges denote possible transitions in method execution, either regular or exceptional.
 */
sealed class ControlFlowGraph(val className: String, val method: MethodSignature) {

    /**
     * Nodes (instruction or basic-block indices) of the control flow graph.
     */
    val nodes: Set<NodeIndex> get() = _nodes
    private val _nodes: MutableSet<NodeIndex> = mutableSetOf()

    /**
     * A mapping from a node to adjacent control-flow edges.
     */
    val allSuccessors: AdjacencyMap get() = _allSuccessors
    private val _allSuccessors: MutableEdgeMap = mutableMapOf()

    val edges: Set<Edge> get() =
        allSuccessors.flatMap { it.value }.toSet()

    val normalEdges: Set<Edge> get() =
        edges.filter { it.label !is EdgeLabel.Exception }.toSet()

    fun hasEdge(src: NodeIndex, dst: NodeIndex): Boolean {
        return _allSuccessors[src]?.any { it.target == dst } ?: false
    }

    open fun addEdge(src: NodeIndex, dst: NodeIndex, label: EdgeLabel) {
        _nodes.add(src)
        _nodes.add(dst)
        _allSuccessors.updateInplace(src, default = mutableSetOf()) {
            add(Edge(src, dst, label))
        }
    }
}

/**
 * Checks if the given opcode corresponds to a recognized JVM conditional jump instruction.
 */
internal fun isRecognizedIfJumpOpcode(opcode: Int): Boolean = when (opcode) {
    Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
    Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
    Opcodes.IFNULL, Opcodes.IFNONNULL,
    Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE
         -> true
    else -> false
}

/**
 * Checks if the given opcode corresponds to a recognized JVM jump/switch instruction.
 */
internal fun isRecognizedJumpOpcode(opcode: Int): Boolean = when (opcode) {
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

internal fun Collection<Edge>.toFormattedString(): String {
    val sb = StringBuilder("")
    val edges = toMutableList().sortedWith(compareBy({ it.source }, { it.target }, { it.label }))
    for ((id, e) in edges.withIndex()) {
        val label = e.label.takeIf { it !is EdgeLabel.FallThrough }
        sb.append("B${e.source} -> B${e.target}")
        sb.append(label?.let { " : $it" }.orEmpty())
        if (id != edges.lastIndex) sb.appendLine()
    }
    return sb.toString()
}