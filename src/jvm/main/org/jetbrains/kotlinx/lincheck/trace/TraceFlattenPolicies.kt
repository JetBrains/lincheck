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
 * A flatten policy, determines how a [TraceNode] graph should be flattened to an ordered list of [TraceNode] elements.
 * A node is flattened by:
 * - Mapping the direct children to flattened lists according to [TraceFlattenPolicy]
 * - Adjusting flattened children according to [beforeFlattenChildren]
 * - Flattening the list of flattened children to a single list of flattened descendants.
 * - Adding `currentNode` to descendants according to [shouldIncludeThisNode].
 * - Adjusting final result (for current node) according to [beforeReturn].
 */
internal interface TraceFlattenPolicy {
    /**
     * Should return true if [traceNode] should be included in the trace according to [TraceFlattenPolicy].
     * Makes sure current node is included in list of descendants.
     */
    fun shouldIncludeThisNode(currentNode: TraceNode): Boolean

    /**
     * Is called before all descendants of current children are flattened.
     * Has the form of List<List<TraceNode>>, where outer list is a list of my direct children.
     * And inner list is a list containing the child and all descendants of that child.
     * Can be used to inject or remove siblings.
     */
    fun beforeFlattenChildren(currentNode: TraceNode, childDescendants: List<List<TraceNode>>): List<List<TraceNode>>

    /**
     * Is called after flattening the childDescendants to a flat list of descendants and right before returning.
     * Includes [currentNode] if [shouldIncludeThisNode].
     */
    fun beforeReturn(currentNode: TraceNode, descendants: List<TraceNode>): List<TraceNode>
}

internal class VerboseTraceFlattenPolicy : TraceFlattenPolicy {

    override fun shouldIncludeThisNode(currentNode: TraceNode): Boolean = when (currentNode) {
        is EventNode -> !currentNode.tracePoint.isVirtual
        else -> true
    }

    override fun beforeFlattenChildren(currentNode: TraceNode, childDescendants: List<List<TraceNode>>): List<List<TraceNode>> {
        return childDescendants
    }

    override fun beforeReturn(currentNode: TraceNode, descendants: List<TraceNode>): List<TraceNode> {
        when (currentNode) {
            is CallNode -> {
                if (!currentNode.isRootCall) return descendants
                val returnedValue = currentNode.tracePoint.returnedValue

                // Dont show hung actor
                if (descendants.size == 1 &&
                    descendants.contains(currentNode) &&
                    returnedValue is ReturnedValueResult.ActorHungResult) return emptyList()

                // Check if result node should be added
                if (descendants.size > 1 && returnedValue is ReturnedValueResult.ActorResult && returnedValue.showAtEndOfActor) {
                    return descendants + ResultNode(currentNode.callDepth + 1, returnedValue, currentNode.returnEventNumber, currentNode.tracePoint)
                }

                return descendants
            }

            else -> return descendants
        }
    }
}

internal class ShortTraceFlattenPolicy : TraceFlattenPolicy {
    override fun shouldIncludeThisNode(currentNode: TraceNode): Boolean = when (currentNode) {
        is EventNode -> with(currentNode) {
            (!tracePoint.isVirtual && (
                    isLast && tracePoint.isBlocking
                            || tracePoint is SwitchEventTracePoint
                            || tracePoint is ObstructionFreedomViolationExecutionAbortTracePoint
                    )
            ) || callDepth == 0
        }
        is CallNode -> currentNode.tracePoint.wasSuspended || currentNode.isRootCall
        is ResultNode -> true
        else -> false
    }

    override fun beforeFlattenChildren(currentNode: TraceNode, childDescendants: List<List<TraceNode>>): List<List<TraceNode>> {
        // Insert siblings if necessary (for short trace)
        // if not verbose and atleast one child has no descendants and one other child has
        if (childDescendants.any { it.isNotEmpty() } && childDescendants.any { it.isEmpty() }) {
            // add all siblings
            return childDescendants.mapIndexed { i, descendantList -> if (descendantList.isEmpty()) listOf(currentNode.children[i]) else descendantList }
        }
        return childDescendants
    }

    override fun beforeReturn(currentNode: TraceNode, descendants: List<TraceNode>): List<TraceNode> {
        when (currentNode) {
            is CallNode -> {
                if (!currentNode.isRootCall) return descendants
                val returnedValue = currentNode.tracePoint.returnedValue

                // Dont show hung actor
                if (descendants.size == 1 &&
                    descendants.contains(currentNode) &&
                    returnedValue is ReturnedValueResult.ActorHungResult) return emptyList()


                // Check if result node should be added
                val nodesToReturn = if (descendants.size > 1 && returnedValue is ReturnedValueResult.ActorResult && returnedValue.showAtEndOfActor) {
                    descendants + ResultNode(currentNode.callDepth + 1, returnedValue, currentNode.returnEventNumber, currentNode.tracePoint)
                    // Or thread start root nodes
                } else if (descendants.size == 1 && descendants.contains(currentNode) && currentNode.tracePoint.isThreadStart) {
                    descendants + currentNode.children
                } else {
                    descendants
                }

                // Append potential last state
                val lastState = currentNode.lastOrNull { it.tracePoint is StateRepresentationTracePoint }
                // if no important state or state is already present in flattened graph: simply return
                if (lastState == null || nodesToReturn.contains(lastState)) return nodesToReturn

                val newLastState = EventNode(0, lastState.tracePoint, lastState.eventNumber)
                return nodesToReturn + listOfNotNull(newLastState)
            }

            else -> return descendants
        }
    }
}

internal fun TraceNode.flattenNodes(policy: TraceFlattenPolicy): List<TraceNode> {
    // Get list of descendants that need to be printed per child 
    val descendantsOfChildren = children.map { it.flattenNodes(policy) }

    // Alter children list according to policy
    val changedDescendantsOfChildren = policy.beforeFlattenChildren(this, descendantsOfChildren)

    val descendants = changedDescendantsOfChildren.flatten()

    val flattened = if (policy.shouldIncludeThisNode(this) || descendants.isNotEmpty()) listOf(this) + descendants else descendants

    return policy.beforeReturn(this, flattened)
}

internal fun SingleThreadedTable<TraceNode>.flattenNodes(flattenPolicy: TraceFlattenPolicy): SingleThreadedTable<TraceNode> =
    map { section ->
        section.forEach { it.setCallDepthOfTree(0) }
        section.flatMap { traceNode -> traceNode.flattenNodes(flattenPolicy) }
    }

//for idea plugin
internal fun SingleThreadedTable<TraceNode>.extractPreExpandedNodes(flattenPolicy: TraceFlattenPolicy): List<TraceNode> =
    flatMap { section -> section.flatMap { it.extractPreExpanded(flattenPolicy).first }}

// virtual trace points are not displayed in the trace
private val TracePoint.isVirtual: Boolean get() = this.isThreadStart() || this.isThreadJoin()

private val TracePoint.isBlocking: Boolean get() = when (this) {
    is MonitorEnterTracePoint, is WaitTracePoint, is ParkTracePoint -> true
    else -> false
}
