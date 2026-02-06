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

internal class LoopNode(
    tracePoint: LoopStartTracePoint,
    eventNumber: Int,
) : TraceNode(eventNumber, tracePoint) {
    override val tracePoint: LoopStartTracePoint get() = super.tracePoint as LoopStartTracePoint

    fun totalIterations(): Int =
        children.sumOf {
            when (it) {
                is IterationNode -> 1
                is IterationRangeNode -> it.count
                else -> 0
            }
        }

    override fun toStringImpl(withLocation: Boolean): String {
        val iters = totalIterations()
        val base = "loop($iters iterations)"
        if (withLocation) return "$base at ${tracePoint.toStringImpl(true).substringAfter(" at ")}"
        else {
            val sb = StringBuilder()
            sb.append("$base:")
            children.forEach { node ->
                val codeFragmentChildren = node.children.map { child ->
                    child.toStringImpl(false)
                }
                codeFragmentChildren.forEach { child ->
                    if (!child.contains("loop(") && !sb.contains(child))
                            sb.append(child).append(";")
                }
            }
            return sb.toString()
        }
    }

    override fun copy(): TraceNode = LoopNode(tracePoint, eventNumber)
}

internal class IterationNode(
    tracePoint: LoopIterationTracePoint,
    eventNumber: Int,
) : TraceNode(eventNumber, tracePoint) {
    override val tracePoint: LoopIterationTracePoint get() = super.tracePoint as LoopIterationTracePoint

    override fun toStringImpl(withLocation: Boolean): String =
        tracePoint.toStringImpl(withLocation)

    override fun copy(): TraceNode = IterationNode(tracePoint, eventNumber)
}

// Used to print <iterations from-to> nodes when compressing iterations
internal class IterationRangeNode(
    startIterationPoint: LoopIterationTracePoint,
    eventNumber: Int,
    val from: Int,
    val to: Int
) : TraceNode(eventNumber, startIterationPoint) {

    override val tracePoint: LoopIterationTracePoint get() = super.tracePoint as LoopIterationTracePoint
    val count: Int get() = to - from + 1

    override fun toStringImpl(withLocation: Boolean): String {
        val loc = if (withLocation) " at ${tracePoint.toStringImpl(true).substringAfter(" at ")}" else ""
        return "<iterations $from-$to>$loc"
    }

    override fun copy(): TraceNode = IterationRangeNode(tracePoint, eventNumber, from, to)
}

internal class RecursionNode(
    val node: CallNode,
    val depth: Int,
    eventNumber: Int,
) : TraceNode(eventNumber, node.tracePoint) {

    override fun toStringImpl(withLocation: Boolean): String {
        return "${node.toStringImpl(withLocation)} [recursion x $depth]"
    }

    override fun copy(): TraceNode = RecursionNode(node, depth, eventNumber)
}

// (stable) Sort on eventNumber
internal fun Column<TraceNode>.reorder(): Column<TraceNode> =
    sortedBy { it.eventNumber }

internal fun traceToTree(threadCount: Int, trace: Trace): MultiThreadedTable<TraceNode> {
    val nodes = MutableList<MutableList<TraceNode>>(threadCount) { mutableListOf() }
    val currentNodePerThread = MutableList<TraceNode?>(threadCount) { null }

    fun addToCurrentContainer(threadId: Int, node: TraceNode) {
        val currNode = currentNodePerThread[threadId]
        if (currNode != null) {
            currNode.addChild(node)
        } else {
            nodes[threadId].add(node)
        }
    }

    // loop over events
    trace.trace.forEachIndexed { eventNumber, event ->
        val currentThreadId = event.iThread

        when (event) {
            is MethodCallTracePoint -> {
                val newNode = CallNode(event, eventNumber)
                addToCurrentContainer(currentThreadId, newNode)
                currentNodePerThread[currentThreadId] = newNode
            }

            is MethodReturnTracePoint -> {
                // Walk up the stack to find the nearest CallNode, as well as implicitly closing any necessary loops
                var currentCallNode = currentNodePerThread[currentThreadId]

                if (currentCallNode == null) {
                    // TODO re-enable later on when the problem with actors will be resolved
                    // error("Return is not allowed here")
                }

                while (currentCallNode != null && currentCallNode !is CallNode) {
                    currentCallNode = currentCallNode.parent
                }

                if (currentCallNode is CallNode) {
                    currentCallNode.returnEventNumber = eventNumber
                    currentNodePerThread[currentThreadId] = currentCallNode.parent
                }
            }

            is LoopStartTracePoint -> {
                val loopNode = LoopNode(event, eventNumber)
                addToCurrentContainer(currentThreadId, loopNode)
                currentNodePerThread[currentThreadId] = loopNode
            }

            is LoopIterationTracePoint -> {
                // We expect either LoopNode or IterationNode
                var curr = currentNodePerThread[currentThreadId]

                // If we are currently inside an iteration, close it by moving up to the LoopNode
                if (curr is IterationNode) {
                    curr = curr.parent
                }
                // If LoopNode is the current container, add iteration as its child and move into it
                if (curr is LoopNode) {
                    val iterNode = IterationNode(event, eventNumber)
                    curr.addChild(iterNode)
                    currentNodePerThread[currentThreadId] = iterNode
                } else {
                    addToCurrentContainer(currentThreadId, EventNode(event, eventNumber))
                }
            }

            is LoopEndTracePoint -> {
                // Ensure we are at the LoopNode level, then pop to its parent
                var curr = currentNodePerThread[currentThreadId]

                // If in an iteration, pop up to Loop
                if (curr is IterationNode) {
                    curr = curr.parent
                }

                // If in Loop, pop up to closing node
                if (curr is LoopNode) {
                    currentNodePerThread[currentThreadId] = curr.parent
                }
            }

            else -> {
                val eventNode = EventNode(event, eventNumber)
                addToCurrentContainer(currentThreadId, eventNode)
            }
        }
    }

    // if an actor was finished unexpectedly, the `MethodReturnTracePoint` could be missing
    for (callNode in currentNodePerThread) {
        if (callNode is CallNode) {
            callNode.returnEventNumber = Int.MAX_VALUE
        }
    }

    return nodes
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
        foldLoopChildren(foldedChildren)
    } else {
        foldedChildren
    }

    for (child in finalChildren) {
        nodeCopy.addChild(child)
    }

    return nodeCopy
}

private fun foldLoopChildren(children: List<TraceNode>): List<TraceNode> {
    val result = mutableListOf<TraceNode>()
    var startIndex = 0

    while (startIndex < children.size) {
        val node = children[startIndex]
        if (node !is IterationNode) {
            result += node
            startIndex++
            continue
        }

        val pattern = iterationPattern(node)

        // While the next nodes match the pattern, extend the range
        var endIndex = startIndex + 1
        while (endIndex < children.size && children[endIndex] is IterationNode && iterationPattern(children[endIndex] as IterationNode) == pattern) {
            endIndex++
        }

        // Make sure it makes sense to fold, more than 1 iteration. Otherwise, just add the original node
        if (endIndex - startIndex >= 2) {
            val first = children[startIndex] as IterationNode
            val last = children[endIndex - 1] as IterationNode

            val from = first.tracePoint.iteration
            val to = last.tracePoint.iteration

            val range = IterationRangeNode(
                startIterationPoint = first.tracePoint,
                eventNumber = first.eventNumber,
                from = from,
                to = to,
            )

            for (bodyChild in first.children) range.addChild(bodyChild)
            result += range
        } else {
            result += node
        }

        startIndex = endIndex
    }

    return result
}

private data class NodePattern(
    val head: String,
    val children: List<NodePattern>
)

private fun iterationPattern(iter: IterationNode): NodePattern =
    NodePattern(
        head = "",
        children = iter.children.map { nodePattern(it) }
    )

private fun nodePattern(node: TraceNode): NodePattern =
    NodePattern(
        head = node.toStringImpl(true),
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