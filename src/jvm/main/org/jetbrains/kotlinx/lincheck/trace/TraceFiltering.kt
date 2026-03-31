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

import org.jetbrains.lincheck.descriptors.CodeLocations
import org.jetbrains.lincheck.jvm.agent.toCanonicalClassName

internal interface TraceFilter {
    fun shouldUnfold(node: TraceNode): Boolean
    fun filterChildren(node: TraceNode): List<TraceNode>
    fun shouldFilter(tracePoint: TracePoint): Boolean
}

internal class ShortenTraceFilter : TraceFilter {

    // a cache storing whether a node can be unfolded or not
    private val unfoldableNodes = mutableMapOf<TraceNode, Boolean>()

//    TODO: see what else can be used to decide unfolding (LoopNode for example)
    override fun shouldUnfold(node: TraceNode): Boolean {
        if (node is CallNode && node.isRootCall && node.tracePoint.isThreadStart) return true
        unfoldableNodes[node]?.let { return it }

        // if (callNode.children.lastOrNull() is LoopNode || callNode.children.lastOrNull() is RecursionNode) {
        //     unfoldableNodes[callNode] = true
        //     return true
        // }

        return node.children.any { child ->
            when (child) {
                is EventNode -> with(child) {
                    !tracePoint.isVirtual && (
                        tracePoint.isBlocking && isLast ||
                        tracePoint is SwitchEventTracePoint ||
                        tracePoint is ObstructionFreedomViolationExecutionAbortTracePoint
                    )
                }
                is CallNode -> {
                    child.tracePoint.wasSuspended ||
                    shouldUnfold(child)
                }
                else -> false
            }
        }.also { decision ->
            unfoldableNodes[node] = decision
        }
    }

    override fun filterChildren(node: TraceNode): List<TraceNode> {
        return node.children.filterNot { shouldFilter(it.tracePoint) }
    }

    override fun shouldFilter(tracePoint: TracePoint): Boolean =
        tracePoint.isThrowableTracePoint
}

internal class VerboseTraceFilter : TraceFilter {
    override fun shouldUnfold(node: TraceNode): Boolean = true

    override fun filterChildren(node: TraceNode): List<TraceNode> {
        return node.children.filterNot { shouldFilter(it.tracePoint) }
    }

    override fun shouldFilter(tracePoint: TracePoint): Boolean =
        tracePoint.isThrowableTracePoint
}

// virtual trace points are not displayed in the trace
internal val TracePoint.isVirtual: Boolean get() =
    this.isThreadStart() || this.isThreadJoin()

// trace points from `Throwable` methods are filter-out from the trace
internal val TracePoint.isThrowableTracePoint: Boolean get() {
    val codeLocation = (this as? CodeLocationTracePoint)?.codeLocation ?: return false
    val stackTraceElement = CodeLocations.stackTrace(context, codeLocation)
    return stackTraceElement.className.toCanonicalClassName() == "java.lang.Throwable"
}

internal val TracePoint.isBlocking: Boolean get() = when (this) {
    is MonitorEnterTracePoint, is WaitTracePoint, is ParkTracePoint -> true
    else -> false
}