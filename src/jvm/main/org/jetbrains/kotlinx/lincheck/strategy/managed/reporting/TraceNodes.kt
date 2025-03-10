/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.reporting


internal fun traceToGraph(trace: Trace): List<TraceNode> {
    
}


internal abstract class TraceNode(val callDepth: Int) {
    val children = mutableListOf<TraceNode>()
    
    abstract fun toString(indent: Int): String
    abstract fun shouldBeIncluded(verbose: Boolean): Boolean
    
    open fun flatten(verbose: Boolean): List<TraceNode> {
        // Get list of descendants in print order
        val descendants = children.flatMap { it.flatten(verbose) }

        // If list is not empty there must be an important event that cannot be collapsed (or verbose)
        if (descendants.isNotEmpty()) return listOf(this) + descendants

        // if verbose or I am important, return myself.
        if (shouldBeIncluded(verbose)) return listOf(this)

        // otherwise nothing to return
        return emptyList()
    }
}

internal class EventNode(callDepth: Int, val tracePoint: TracePoint, val lastEvent: Boolean = false): TraceNode(callDepth) {
    override fun toString(indent: Int): String = tracePoint.toString()
    // should be included if
    // tracePoint is not virtual and one of the following holds:
    // Is a last blocking event OR is a switch OR is
    override fun shouldBeIncluded(verbose: Boolean): Boolean = !tracePoint.isVirtual && (
            lastEvent && tracePoint.isBlocking
                    || tracePoint is SwitchEventTracePoint
                    || tracePoint is ObstructionFreedomViolationExecutionAbortTracePoint
                    || verbose
            )
    
    private val TracePoint.isBlocking: Boolean get() = when (this) {
        is MonitorEnterTracePoint, is WaitTracePoint, is ParkTracePoint -> true
        else -> false
    }
    
    // virtual trace points are not displayed in the trace
    private val TracePoint.isVirtual: Boolean get() = when (this) {
        is ThreadStartTracePoint, is ThreadJoinTracePoint -> true
        else -> false
    }
}

internal class ActorNode(callDepth: Int, val actorRepresentation: String, val resultRepresentation: String): TraceNode(callDepth) {
    override fun toString(indent: Int): String = "$actorRepresentation${if (resultRepresentation.isNotEmpty()) ": $resultRepresentation" else ""}"
    override fun shouldBeIncluded(verbose: Boolean): Boolean = true
    override fun flatten(verbose: Boolean): List<TraceNode> {
        val descendants = super.flatten(verbose)
        if (descendants.size > 1) return descendants + ResultNode(callDepth, resultRepresentation)
        return descendants
    }
}

internal class ResultNode(callDepth: Int, val resultRepresentation: String): TraceNode(callDepth) {
    override fun toString(indent: Int): String = "result: $resultRepresentation"
    override fun shouldBeIncluded(verbose: Boolean): Boolean = verbose
}

internal class CallNode(callDepth: Int, val tracePoint: MethodCallTracePoint): TraceNode(callDepth) {
    override fun toString(indent: Int): String = tracePoint.toString()
    override fun shouldBeIncluded(verbose: Boolean): Boolean = tracePoint.wasSuspended || verbose
}