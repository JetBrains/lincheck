/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.trace

/**
 * Represents a single node in the hierarchical trace structure.
 *
 * @property callDepth Indicates the depth of the current call within the trace.
 * @property eventNumber The sequence number event contained by this node, or contained by the first event descendant.
 * @property tracePoint The associated trace point.
 * @property iThread Thread id of the thread that executed this part of the trace.
 * @property actorId Parent actor id of this trace point.
 * @property children List of direct children of this node.
 * @property parent The parent node of this trace node. Can be null if this node is the root.
 * @property isLast Indicates whether this node is the last node of the actor.
 */
internal abstract class TraceNode(val eventNumber: Int, open val tracePoint: TracePoint) {
    val iThread = tracePoint.iThread
    val actorId = tracePoint.actorId
    
    private val _children = mutableListOf<TraceNode>()
    val children: List<TraceNode> get() = _children
    
    var parent: TraceNode? = null
        private set
    
    val isLast: Boolean get() {
        if (parent == null) return true
        return isLastChild && parent?.isLast != false
    }

    val isLastChild: Boolean get() =
        this === parent?.children?.last()
    
    fun addChild(node: TraceNode) {
        _children.add(node)
        node.parent = this
    }

    internal abstract fun toStringImpl(withLocation: Boolean): String
    override fun toString(): String = toStringImpl(withLocation = true)

    fun lastOrNull(predicate: (TraceNode) -> Boolean): TraceNode? {
        val last = children.mapNotNull { it.lastOrNull(predicate) }.lastOrNull()
        if (last != null) return last
        if (predicate(this)) return this
        return null
    }

    /**
     * Checks if the [predicate] holds for the current node or any of its descendants.
     */
    fun contains(predicate: (TraceNode) -> Boolean): Boolean =
        predicate(this) || children.any { it.contains(predicate) }

    /**
     * Returns the level of the first node satisfying the [predicate], or -1 if no match was found.
     *
     * @param depth Maximum depth to check.
     * @return level at which the first node satisfying the predicate was found,
     *   or -1 if no match was found
     */
    fun findLevelOf(depth: Int, predicate: (TraceNode) -> Boolean): Int {
        if (depth <  0) return -1
        if (depth == 0) return if (predicate(this)) 0 else -1

        if (predicate(this)) return 0
        for (child in children) {
            val level = child.findLevelOf(depth - 1, predicate)
            if (level >= 0) return (level + 1)
        }
        return -1
    }

    /**
     * Returns a flattened list of all nodes in the tree, including this node.
     */
    fun flatten(): List<TraceNode> =
        children.flatMap { it.flatten() } + this

    /**
     * Shallow copy without children
     */
    abstract fun copy(): TraceNode 
}

internal class EventNode(
    tracePoint: TracePoint,
    eventNumber: Int,
): TraceNode(eventNumber, tracePoint) {

    override fun toStringImpl(withLocation: Boolean): String =
        tracePoint.toStringImpl(withLocation)

    override fun copy(): TraceNode = EventNode(tracePoint, eventNumber)
}

internal class CallNode(
    tracePoint: MethodCallTracePoint,
    eventNumber: Int,
): TraceNode(eventNumber, tracePoint) {
    override val tracePoint: MethodCallTracePoint get() = super.tracePoint as MethodCallTracePoint
    val isRootCall get() = (parent == null)
    var returnEventNumber: Int = -1

    var isActor = tracePoint.isActor
        private set

    fun treatAsActor() {
        isActor = true
    }

    override fun toStringImpl(withLocation: Boolean): String =
        tracePoint.toStringImpl(withLocation)

    override fun copy(): TraceNode = CallNode(tracePoint, eventNumber)
        .also { it.returnEventNumber = returnEventNumber}
}

// Is not part of an initial tree, is only added during flattening or for empty GPMC result
internal class ResultNode(val actorResult: ReturnedValueResult, eventNumber: Int, tracePoint: TracePoint)
    : TraceNode(eventNumber, tracePoint) {

    override fun toStringImpl(withLocation: Boolean): String =
        "result: ${actorResult.resultRepresentation}"

    override fun copy(): TraceNode = ResultNode(actorResult, eventNumber, tracePoint)
}

// (stable) Sort on eventNumber
internal fun Column<TraceNode>.reorder(): Column<TraceNode> =
    sortedBy { it.eventNumber }

internal fun traceToTree(threadCount: Int, trace: Trace): MultiThreadedTable<TraceNode> {
    val nodes = MutableList<MutableList<TraceNode>>(threadCount) { mutableListOf() }
    val currentNodePerThread = MutableList<CallNode?>(threadCount) { null }

    // loop over events
    trace.trace.forEachIndexed { eventNumber, event ->
        val currentThreadId = event.iThread
        val currentCallNode = currentNodePerThread[currentThreadId]

        when (event) {
            is MethodCallTracePoint -> {
                val newNode = CallNode(event, eventNumber)
                if (currentCallNode == null) {
                    nodes[currentThreadId].add(newNode)
                }
                currentCallNode?.addChild(newNode)
                currentNodePerThread[currentThreadId] = newNode
            }

            is MethodReturnTracePoint -> {
                currentCallNode?.returnEventNumber = eventNumber
                currentNodePerThread[currentThreadId] = currentCallNode?.parent as? CallNode
                if (currentCallNode == null) {
                    // TODO re-enable later on when the problem with actors will be resolved
                    // error("Return is not allowed here")
                }
            }

            else -> {
                val eventNode = EventNode(event, eventNumber)
                if (currentCallNode != null) {
                    currentCallNode.addChild(eventNode)
                } else {
                    nodes[currentThreadId].add(eventNode)
                }
            }
        }
    }

    // if an actor was finished unexpectedly, the `MethodReturnTracePoint` could be missing
    for (callNode in currentNodePerThread) {
        callNode?.returnEventNumber = Int.MAX_VALUE
    }

    return nodes
}
