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

import kotlin.collections.plus

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
internal abstract class TraceNode(var callDepth: Int, val eventNumber: Int, open val tracePoint: TracePoint) {
    val iThread = tracePoint.iThread
    val actorId = tracePoint.actorId
    
    private val _children = mutableListOf<TraceNode>()
    val children: List<TraceNode> get() = _children
    
    var parent: TraceNode? = null
        private set
    
    // Am i last event. Check at parents and all ancestors
    val isLast: Boolean get() {
        if (parent == null) return true
        return parent?.children?.last() === this && parent?.isLast != false
    }
    
    fun addChild(node: TraceNode) {
        _children.add(node)
        node.parent = this
    }

    abstract override fun toString(): String

    // Shifts stackTrace to the left
    fun decrementCallDepthOfTree() {
        callDepth--
        children.forEach { it.decrementCallDepthOfTree() }
    }

    fun lastOrNull(predicate: (TraceNode) -> Boolean): TraceNode? {
        val last = children.mapNotNull { it.lastOrNull(predicate) }.lastOrNull()
        if (last != null) return last
        if (predicate(this)) return this
        return null
    }
    
    // for idea plugin
    fun extractPreExpanded(policy: TraceFlattenPolicy): Pair<List<TraceNode>, Boolean> { 
        val (preExpanded, shouldChildBeIncluded) = children
            .map { it.extractPreExpanded(policy) }
            .unzip()
            .let { it.first.flatten() to it.second.any() }
        
        if (shouldChildBeIncluded) return preExpanded + this to true
        if (policy.shouldIncludeThisNode(this)) return preExpanded to true
        check(preExpanded.isEmpty()) { "Expected pre expanded set to be empty" }
        return preExpanded to false
    }

    /**
     * Checks if the [predicate] holds for any of this [TraceNode] descendants including this [TraceNode].
     */
    fun containsDescendant(predicate: (TraceNode) -> Boolean): Boolean =
        predicate(this) || children.any { it.containsDescendant(predicate) }

    /**
     * Shallow copy without children
     */
    abstract fun copy(): TraceNode 
}

internal class EventNode(
    callDepth: Int,
    tracePoint: TracePoint,
    eventNumber: Int,
): TraceNode(callDepth, eventNumber, tracePoint) {
    override fun toString(): String = tracePoint.toString()
    override fun copy(): TraceNode = EventNode(callDepth, tracePoint, eventNumber)
}

internal class CallNode(
    callDepth: Int,
    tracePoint: MethodCallTracePoint,
    eventNumber: Int,
): TraceNode(callDepth, eventNumber, tracePoint) {
    override val tracePoint: MethodCallTracePoint get() = super.tracePoint as MethodCallTracePoint
    val isRootCall get() = callDepth == 0

    override fun toString(): String = tracePoint.toString()
    override fun copy(): TraceNode = CallNode(callDepth, tracePoint, eventNumber)
    
    internal fun createResultNodeForEmptyActor() =
        ResultNode(callDepth + 1, tracePoint.returnedValue as ReturnedValueResult.ActorResult, eventNumber, tracePoint)
}

// Is not part of initial graph, is only added during flattening or for empty GPMC result
internal class ResultNode(callDepth: Int, val actorResult: ReturnedValueResult.ActorResult, eventNumber: Int, tracePoint: TracePoint)
    : TraceNode(callDepth, eventNumber, tracePoint) {
    override fun toString(): String = "result: ${actorResult.resultRepresentation}"
    override fun copy(): TraceNode = ResultNode(callDepth, actorResult, eventNumber, tracePoint)
}

// (stable) Sort on eventNumber
internal fun SingleThreadedTable<TraceNode>.reorder(): SingleThreadedTable<TraceNode> =
    map { section -> section.sortedBy { it.eventNumber } }

/**
 * Returns [preActos, parrallelActors, postActors], no threads!!
 */
internal fun traceToGraph(trace: Trace): SingleThreadedTable<TraceNode> {
    val callNodes = mutableMapOf<TracePoint, CallNode>()
    val sections = mutableListOf<List<TraceNode>>()
    var currentSection = mutableListOf<TraceNode>()
    
    // loop over events
    trace.trace.forEachIndexed { eventNumber, event ->
        
        if (event is SectionDelimiterTracePoint) {
            currentSection = mutableListOf()
            sections.add(currentSection)
            return@forEachIndexed
        }
        
        // Add callpath to graph where needed and return last element in path
        val lastCallBeforeEvent = event.callStackTrace.foldIndexed(null) { depth, prevNode: CallNode?, call ->
            if (event is SwitchEventTracePoint && !callNodes.containsKey(call.tracePoint) && prevNode != null) return@foldIndexed prevNode
            callNodes.computeIfAbsent(call.tracePoint) {
                val newNode = CallNode(depth, call.tracePoint, eventNumber)
                if (call.tracePoint.isRootCall) currentSection.add(newNode)
                prevNode?.addChild(newNode)
                newNode
            }
        }
        
        // if event has trace add to end of path in graph
        if (lastCallBeforeEvent != null) {
            val eventNode = EventNode(lastCallBeforeEvent.callDepth + 1, event, eventNumber)
            lastCallBeforeEvent.addChild(eventNode)
            return@forEachIndexed
        }
        
        // if actor add node
        if (event is MethodCallTracePoint && event.isRootCall) {
            callNodes.computeIfAbsent(event) { 
                val callNode = CallNode(0, event, eventNumber)
                currentSection.add(callNode)
                callNode
            }
            return@forEachIndexed
        }
        
        // Hack to circumvent problem with empty stack traces after coroutine suspend TODO fix in `ManagedStrategy`
        if (event.callStackTrace.isEmpty()) {
            val actor = currentSection.first {it.actorId == event.actorId && it.iThread == event.iThread}
            actor.addChild(EventNode(1, event, eventNumber))
            return@forEachIndexed
        }
        
        check(false) { "Event has no trace that leads to it" }
    }
    return sections
}
