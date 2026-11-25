/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.lincheck_trace

import org.jetbrains.lincheck.runner.ExecutionPart
import org.jetbrains.lincheck.util.ExceptionResult
import org.jetbrains.lincheck.util.isNoResult

internal fun Trace.removeRedundantSectionDelimiters(): Trace {
    val firstTracePoint = trace.firstOrNull() ?: return this
    if (firstTracePoint !is SectionDelimiterTracePoint) return this
    if (trace.count { it is SectionDelimiterTracePoint } > 1) return this
    return Trace(trace.drop(1), threadNames)
}

/**
 * Removes the validation section from the trace.
 *
 * @return a new trace with the validation section removed if applicable,
 *   or the original trace if no removal is necessary.
 */
internal fun Trace.removeValidationSection(): Trace {
    val newTrace = this.trace.takeWhile {
        !(it is SectionDelimiterTracePoint && it.executionPart == ExecutionPart.VALIDATION)
    }
    return Trace(newTrace, this.threadNames)
}

/**
 * Numbers actor exceptions in the same order as the reported exception stack traces.
 */
internal fun Trace.numberExceptionResults() {
    trace.filterIsInstance<MethodCallTracePoint>()
        .filter { it.isActor && it.returnedValue is ExceptionResult }
        .sortedWith(compareBy({ it.actorId }, { it.iThread }))
        .forEachIndexed { index, tracePoint -> tracePoint.exceptionNumber = index + 1 }
}

/**
 * Removes the artificial GPMC actor method call from the trace and adjusts the trace structure.
 *
 * A hung GPMC run has no matching method return, so only the opening call is removed in that case.
 *
 * @return A new trace with the GPMC actor method call and its matching return, when present, removed.
 * @throws IllegalStateException If the input trace does not represent GPMC run trace.
 */
// TODO support multiple root nodes in GPMC mode, needs discussion on how to deal with `result: ...`
internal fun Trace.removeGPMCLambda(): Trace {
    val newTrace = this.trace.toMutableList()

    fun TracePoint.isGPMCRunMethodCall(): Boolean =
        this is MethodCallTracePoint &&
        this.callType == MethodCallTracePoint.CallType.THREAD_RUN

    check(newTrace[0].isGPMCRunMethodCall()) {
        "In GPMC trace the first trace point must be a run() method call"
    }
    check(newTrace[0].eventId == 0) {
        "In GPMC trace the first trace point must be a run() method call with eventId = 0, " +
        "actual eventId = ${newTrace[0].eventId}"
    }

    val gpmcCallIndex = 0
    val gpmcResultIndex = newTrace.indexOfFirst {
        it is MethodReturnTracePoint && it.callEventId == 0
    }
    if (gpmcResultIndex >= 0) {
        newTrace.removeAt(gpmcResultIndex)
    }

    newTrace.removeAt(gpmcCallIndex)

    return Trace(newTrace, this.threadNames)
}

/**
 * Filters out empty "hung" actor nodes from the list of trace nodes.
 *
 * @return A new list of [TraceNode] instances with all empty "hung" actor nodes removed.
 */
internal fun List<TraceNode>.removeEmptyHungActors(): List<TraceNode> {
    return filterNot { node ->
        node is CallNode &&
        node.isActor &&
        node.children.isEmpty() &&
        // TODO: use special result object to denote hung result
        node.tracePoint.returnedValue.isNoResult()
    }
}

/**
 * Appends actor result nodes [ResultNode] to the list of [TraceNode] elements where applicable.
 */
internal fun List<TraceNode>.appendResultNodes() {
    val nodes = this
    for (node in nodes) {
        if (node !is CallNode || !node.isRootCall || !node.isActor) continue

        val returnedValue = node.tracePoint.returnedValue

        // Do not add an empty hung actor
        if (node.isActor &&
            node.children.size == 1 &&
            returnedValue.isNoResult()
        ) continue

        // Do not add a result node for an actor with no children
        if (node.isActor &&
            node.children.isEmpty()
        ) continue

        if (!returnedValue.showAtMethodCallEnd()) continue

        val resultNode = ResultNode(node.returnEventNumber, node.tracePoint)
        node.addChild(resultNode)
    }
}

internal fun foldEquivalentLoopIterations(
    table: MultiThreadedTable<TraceNode>
): MultiThreadedTable<TraceNode> {
    return table
        .map { threadNodes ->
            threadNodes.map { foldNode(it) }.toMutableList()
        }
}

/**
 * Folds equivalent loop iterations in the given [node] and its children recursively.
 */
private fun foldNode(node: TraceNode): TraceNode {
    val nodeCopy = node.copy()

    val foldedChildren = node.children.map { foldNode(it) }

    val finalChildren = if (nodeCopy is LoopNode) {
        splitLoopIterationsByThreadSwitchedAndFoldIterations(foldedChildren)
    } else {
        foldedChildren
    }

    for (child in finalChildren) {
        nodeCopy.addChild(child)
    }

    return nodeCopy
}

data class Cycle(
    val bestCount: Int,
    val bestPeriod: Int
)

fun <T> List<T>.findCycle(
    startIndex: Int = 0,
    comparator: (T, T) -> Boolean = { a, b -> a == b }
): Cycle? {
     // Calculate the maximum possible period length in iterations
     // This should handle cases like: A, A, A, A (max period 1), and A, B, C, A, B, C (max period 3)
    val maxPeriod = (size - startIndex) / 2

    for (period in 1..maxPeriod) {
        var currentCount = 1
        var endIndex = startIndex + period

        // Check how many times the sequence of length 'period' repeats
        while (endIndex + period <= size) {
            var isMatch = true

            for (i in 0 until period) {
                if (!comparator(this[startIndex + i], this[endIndex + i])) {
                    isMatch = false
                    break
                }
            }

            if (isMatch) {
                currentCount++
                endIndex += period
            } else {
                break
            }
        }

        if (currentCount > 1) {
            // Stop at the smallest period with the longest repetition
            return Cycle(bestCount = currentCount, bestPeriod = period)
        }
    }

    return null
}

/**
 * Splits loop node's children at loop iterations containing switch events,
 * folds each non-switch section independently (see [foldLoopIterations] for details),
 * and reassembles.
 */
private fun splitLoopIterationsByThreadSwitchedAndFoldIterations(children: List<TraceNode>): List<TraceNode> {
    val result = mutableListOf<TraceNode>()
    var sectionStart = 0

    for (i in children.indices) {
        if (children[i].hasSwitchEvent()) {
            // Fold the uniform section before this switch iteration
            if (i > sectionStart) {
                result += foldLoopIterations(children.subList(sectionStart, i))
            }
            // Add the switch iteration as-is
            result += children[i]
            sectionStart = i + 1
        }
    }
    // Fold remaining section after the last switch
    if (sectionStart < children.size) {
        result += foldLoopIterations(children.subList(sectionStart, children.size))
    }

    return result
}

private fun TraceNode.hasSwitchEvent(): Boolean =
    children.any { it is EventNode && it.tracePoint is SwitchEventTracePoint }

/**
 * Folds equivalent loop iterations in the given list of [children] by detecting cycles and applying folding rules.
 * The folding rules are as follows:
 *
 * Case (1): fully equal iterations
 *   ```
 *   A, A, A, A, ...
 *   <iterations 1 - 10>
 *     A
 *   ```
 *
 * Case (2): partially equal iterations
 *   ```
 *   A(a), A(b), A(c), ...., A(j)
 *   <loop(10 iterations)>
 *     <iteration 1>
 *       A(a)
 *     ...
 *     <iteration 10>
 *       A(j)
 * ```
 *
 * Case (3): cycle with the period > 1 (either fully or partially equal)
 *   ```
 *   A, B, C, A, B, C, ...
 *   <loop(12 iterations)>
 *     <iteration 1>
 *       A
 *     <iteration 2>
 *       B
 *     <iteration 3>
 *       C
 *     ...
 *     <iteration 10>
 *       A
 *     <iteration 11>
 *       B
 *     <iteration 12>
 *       C
 * ```
 */
private fun foldLoopIterations(children: List<TraceNode>): List<TraceNode> {
    val result = mutableListOf<TraceNode>()
    var startIndex = 0

    while (startIndex < children.size) {
        val startNode = children[startIndex]

        val cycle = children.findCycle(startIndex) { nodeA, nodeB ->
            nodePattern(nodeA) == nodePattern(nodeB) // Match structurally first to check for potential cycles
        }

        if (cycle != null && cycle.bestCount > 1) {
            val totalNodesCovered = cycle.bestCount * cycle.bestPeriod

            // Check if the cycle is strictly equal across all iterations for Case 1
            var isStrictlyEqual = true
            for (i in cycle.bestPeriod until totalNodesCovered) {
                val baseNode = children[startIndex + (i % cycle.bestPeriod)]
                val currNode = children[startIndex + i]
                if (strictNodePattern(baseNode) != strictNodePattern(currNode)) {
                    isStrictlyEqual = false
                    break
                }
            }

            val firstNode = children[startIndex]
            val lastNode = children[startIndex + totalNodesCovered - 1]

            val startIter = if (firstNode is LoopIterationNode) firstNode.from else 1
            val endIter = if (lastNode is LoopIterationNode) lastNode.to else cycle.bestCount

            if (isStrictlyEqual && cycle.bestPeriod == 1) {
                // Case 1: Fully equal iterations -> Single IterationNode covering the range
                val rangeNode = LoopIterationNode(
                    tracePoint = firstNode.tracePoint,
                    eventNumber = firstNode.eventNumber,
                    from = startIter,
                    to = endIter
                )
                firstNode.children.forEach { child ->
                    rangeNode.addChild(deepCopyNode(child))
                }
                result += rangeNode
            } else {
                // Cases 2 & 3: Partially equal or period > 1
                val lastPeriodStart = startIndex + totalNodesCovered - cycle.bestPeriod

                // Add the first period of the cycle
                for (i in 0 until cycle.bestPeriod) {
                    result += deepCopyNode(children[startIndex + i])
                }

                if (cycle.bestCount > 2) {
                    // Add Ellipsis(...) node to indicate the folding.
                    // Use the event number of the last folded iteration so that
                    // when the multi-threaded trace is sorted globally by event number,
                    // the ellipsis is placed just before the last visible period.
                    val eventNumber = children[lastPeriodStart - 1].eventNumber
                    result += EllipsisNode(firstNode.tracePoint, eventNumber)
                }

                // Add the last period of the cycle
                for (i in 0 until cycle.bestPeriod) {
                    result += deepCopyNode(children[lastPeriodStart + i])
                }
            }
            startIndex += totalNodesCovered
        } else {
            result += startNode
            startIndex++
        }
    }

    return result
}

private fun deepCopyNode(node: TraceNode): TraceNode {
    val copy = node.copy()
    node.children.forEach { child ->
        copy.addChild(deepCopyNode(child))
    }
    return copy
}

private data class NodePattern(
    val head: String,
    val children: List<NodePattern>
)

private fun normalizeNodeHead(rawString: String): String {
    return if (rawString.startsWith("<iteration")) {
        "<iteration>"
    } else
        rawString

// Normalize array indices (since they are part of the fieldName, not the value). Not sure if needed though
//    if (result.contains('[')) {
//        result = result.replace(Regex("\\[.*?\\]"), "[...]")
//    }
}

// Strict equality check (Case 1)
private fun strictNodePattern(node: TraceNode): NodePattern =
    NodePattern(
        head = normalizeNodeHead(node.tracePoint.toString(withLocation = false, withValues = true)),
        children = node.children.map { strictNodePattern(it) }
    )

// Structural equality check (Cases 2 & 3)
private fun nodePattern(node: TraceNode): NodePattern =
    NodePattern(
        head = normalizeNodeHead(node.tracePoint.toString(withLocation = false, withValues = false)),
        children = node.children.map { nodePattern(it) }
    )

internal fun foldRecursiveCalls(
    table: MultiThreadedTable<TraceNode>
): MultiThreadedTable<TraceNode> {
    return table.map { threadNodes ->
        threadNodes.map { foldRecursion(it) }.toMutableList()
    }.toMutableList()
}

private fun foldRecursion(node: TraceNode): TraceNode {
    // check if node is the start of a recursion chain
    if (node is CallNode) {
        val chain = detectRecursionChain(node)

        if (chain.size > 1) {
            val depth = chain.size
            val tailNode = chain.last()

            val recursionNode = RecursionNode(
                node = node,
                depth = depth,
                eventNumber = node.eventNumber
            )

            // Skip folding the recursive child of the head node, as it will be represented by the recursion node itself. We then apply foldRecursion to the rest of the children to handle nested structures.
            val nextInChain = chain[1]
            node.children.forEach { child ->
                if (child !== nextInChain) {
                    recursionNode.addChild(foldRecursion(child))
                }
            }

            // Apply fold to the children of the tail node to handle any nested structures within the tail
            tailNode.children.forEach { child ->
                recursionNode.addChild(foldRecursion(child))
            }

            return recursionNode
        }
    }

    // Propagate folding to children of non-recursive nodes
    val newNode = node.copy()
    node.children.forEach { child ->
        newNode.addChild(foldRecursion(child))
    }
    return newNode
}

// Returns the list of CallNodes forming the recursion chain (including head and tail).
// If no recursion is detected, returns a list with only the head.
private fun detectRecursionChain(head: CallNode): List<CallNode> {
    val chain = mutableListOf<CallNode>()
    chain.add(head)

    var current = head
    while (true) {
        // Find a child that calls the same method
        val recursiveChild = current.children.firstOrNull {
            isRecursiveChild(current, it)
        } as? CallNode

        if (recursiveChild == null) {
            break
        }

        chain.add(recursiveChild)
        current = recursiveChild
    }
    return chain
}

private fun isRecursiveChild(parent: CallNode, child: TraceNode): Boolean {
    return child is CallNode &&
            child.tracePoint.methodName == parent.tracePoint.methodName &&
            child.tracePoint.className == parent.tracePoint.className
}
