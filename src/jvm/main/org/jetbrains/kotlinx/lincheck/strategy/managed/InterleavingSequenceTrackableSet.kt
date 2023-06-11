/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

/**
 * Helps find max prefix length without cycle on suffix
 *
 * For example, for input data [1, 2, 4, 3, 4, 3, 4] will return 4, as suffix cycle is [3, 4],
 *  so the maximum prefix we can get not to overlap the cycle repetitions is the prefix of size 4: [1, 2, 4, 3].
 *
 * For spin-cycles detection, it's important to get the maximum size suffix cycle,
 * which doesn't consist of another, shorter suffix cycle.
 *
 * For example, if we hung in the loop with a history (code locations): [3, 5, 2, 1, 1, 2, 1, 1, 2, 1, 1, 2, 1, 1],
 *  we will want to cut 9 last events to show these executions: [3, 5, 2, 1, 1] (prefix and the first cycle occurrence).
 *
 * @param elements list of elements, where cycle is excepted on the suffix
 *
 * @return elements count from the beginning of the list
 */
internal fun <T> findMaxPrefixLengthWithNoCycleOnSuffix(elements: List<T>): Int {
    if (elements.isEmpty() || elements.size == 1) return 0
    val lastIndex = elements.lastIndex
    var targetCycleLength = elements.size
    var minLastPositionNotRelatedToCycle = elements.lastIndex

    for (i in lastIndex - 1 downTo elements.size / 2 - 1) {
        var j = 0
        // trying to find the second cycle segment occurrence
        while (i - j >= 0 && elements[i - j] == elements[lastIndex - j] && lastIndex - j > i) {
            j++
        }
        if (lastIndex - j != i) continue // cycle not found
        val cycleLength = elements.size - (i + 1)
        val lastPositionNotRelatedToCycle = if (i - j >= 0) {
            findLastIndexNotRelatedToCycle(elements = elements, cycleLength = cycleLength, startPosition = i - j)
        } else -1
        if (minLastPositionNotRelatedToCycle > lastPositionNotRelatedToCycle) {
            minLastPositionNotRelatedToCycle = lastPositionNotRelatedToCycle
            targetCycleLength = cycleLength
        }
    }

    return if (targetCycleLength == elements.size) {
        elements.size // cycle not found
    } else minLastPositionNotRelatedToCycle + targetCycleLength + 1 // number of prefix elements with first cycle
}

/**
 * @return the last index of the element doesn't belong to any suffix cycle iteration, or -1, if all elements belong to the cycle
 */
private fun <T> findLastIndexNotRelatedToCycle(elements: List<T>, cycleLength: Int, startPosition: Int): Int {
    var position = startPosition

    while (position >= 0) {
        val elementFromCycle = elements[elements.lastIndex - ((elements.lastIndex - position) % cycleLength)]
        val elementToMatch = elements[position]
        if (elementFromCycle != elementToMatch) {
            return position
        }

        position--
    }

    return -1
}

/**
 * This class holds information about executions in exact thread and serves to find spin-cycles
 */
internal data class InterleavingHistoryNode(
    val threadId: Int,
    var executions: Int = 0,
    var cycleOccurred: Boolean = false,
    /*
     * Hash code calculated by execution ids of this switch,
     * which is being used to find cycles in interleaving sequences.
     * It is required as we want to distinguish two execution sequences of the same count in the same thread,
     * but started from different locations.
     * In return, it allows us to avoid (in most cases, in practice) cutting execution sequences,
     * which are not part of the spin cycle.
     * For example, suppose we had 3 executions in thread with id 1, then 3 executions with id 2, and after that we ran into a spin cycle
     * with period 3. When we will be making a decision on how many context switches (in the trace collection phase) allow before halt,
     * we would like to keep the first two sequences of execution, because they are not parts of the cycle.
     * So, this field will give us such ability to separate executions.
     */
    var executionLocationsHash: Int = 0 // no execution is performed in this thread for now
) {

    init {
        require(executions >= 0)
    }

    /**
     * @param executionIdentity location identification which will be used for [executionLocationsHash]
     */
    fun addExecution(executionIdentity: Int) {
        executions++
        executionLocationsHash = executionLocationsHash xor executionIdentity
    }

    fun asNodeCorrespondingToCycle(
        elementsToTakeFromHistory: Int, executionLocationsBeforeCycle: List<Int>
    ): InterleavingHistoryNode {
        check(executions >= elementsToTakeFromHistory)

        return InterleavingHistoryNode(
            threadId = threadId,
            executionLocationsHash = executionLocationsBeforeCycle.reduceRight { a, b -> a xor b },
            executions = elementsToTakeFromHistory,
            cycleOccurred = true
        )
    }

    fun copy() = InterleavingHistoryNode(
        threadId = threadId,
        executions = executions,
        cycleOccurred = cycleOccurred,
        executionLocationsHash = executionLocationsHash
    )
}


/**
 * Structure to store chains of thread executions and switches for exact interleaving to detect spin-locks early.
 * For more effective memory usage, it stores chains as a prefix tree.
 */
internal class InterleavingSequenceTrackableSet {
    /*
    Here is an example of such tree (notation: [threadId: executionsCount], '!' sign present if spin-lock occurred):
    root
    └── 1 : 2
        ├── 1 : 13
        │   └── 0 : 4 [!]
        └── 0 : 5 [!]

    Here we can see, the chains added:
    [1: 15] [0: 4 !]
    [1: 2] [0: 5 !]

    Let's consider this chain: [1: 15] [0: 4 !].
    That stands for the fact that 15 executions in the thread with id 1 thread
     and then 4 executions in the thread with id 0 leads to a spin-lock.
     */

    /**
     * Thread id to node map
     */
    private val rootTransitions: MutableMap<Int, ChainNode> = mutableMapOf()

    val cursor = Cursor()

    /**
     * Add a new chain to the set
     */
    fun addBranch(chain: List<InterleavingHistoryNode>) {
        require(chain.isNotEmpty()) { "Internal error: Cycle chain can't be empty" }
        require(chain.last().cycleOccurred) { "Internal error: Chain must represent a sequence of events that leads to cycle" }

        val firstElement = chain.first().threadId
        // If we already have a chain with that threadId - merge it
        rootTransitions[firstElement]?.let {
            it.addBranch(chain, startIndex = 0)
            return
        }
        // Otherwise, create a new chain and transition from the root
        val (nodesChainRoot, leaf) = wrapChain(chain)
        rootTransitions[firstElement] = nodesChainRoot
        // Move the cursor to this position to make caller code know further
        // that we're still in the cycle if no context switch is able
        cursor.setTo(leaf)
    }

    /**
     * Wrapper of [InterleavingHistoryNode] to add a transition map to from current node
     */
    internal inner class ChainNode(
        val node: InterleavingHistoryNode,
        var transitions: MutableMap<Int, ChainNode>
    ) {

        /**
         * Merges a new chain starting from the current node from specified index
         */
        fun addBranch(newChain: List<InterleavingHistoryNode>, startIndex: Int) {
            if (startIndex > newChain.lastIndex) return
            val firstNew = newChain[startIndex]
            check(firstNew.threadId == node.threadId)

            val threadId = node.threadId
            when {
                node.executions == firstNew.executions -> mergeFurtherOrAddNewBranch(newChain, startIndex + 1)

                node.executions < firstNew.executions -> {
                    firstNew.executions -= node.executions
                    mergeFurtherOrAddNewBranch(newChain, startIndex)
                }

                else -> { // node.operations > first.operations
                    if (startIndex == newChain.lastIndex) return

                    val delta = node.executions - firstNew.executions
                    val nextNode = ChainNode(
                        node = InterleavingHistoryNode(
                            threadId = threadId,
                            executions = delta,
                            cycleOccurred = node.cycleOccurred,
                            executionLocationsHash = node.executionLocationsHash,
                        ),
                        transitions = transitions
                    )
                    node.executions = firstNew.executions
                    node.cycleOccurred = firstNew.cycleOccurred
                    node.executionLocationsHash = firstNew.executionLocationsHash

                    val (newChainRoot, newChainLeaf) = wrapChain(newChain, startIndex + 1)
                    transitions = mutableMapOf(
                        threadId to nextNode,
                        newChainRoot.node.threadId to newChainRoot
                    )
                    cursor.setTo(newChainLeaf)
                }
            }
        }

        private fun mergeFurtherOrAddNewBranch(newChain: List<InterleavingHistoryNode>, startIndex: Int) {
            val first = if (startIndex <= newChain.lastIndex) newChain[startIndex] else return

            val transition = transitions[first.threadId]
            if (transition == null) {
                val (wrappedChainRoot, leaf) = wrapChain(newChain, startIndex)
                transitions[first.threadId] = wrappedChainRoot
                cursor.setTo(leaf)
            } else {
                transition.addBranch(newChain, startIndex)
            }
        }

        override fun toString(): String {
            val suffix = if (node.cycleOccurred) " [!]" else ""

            return node.let { "${it.threadId} : ${it.executions} (${it.executionLocationsHash})$suffix" }
        }
    }


    /**
     * Wraps a new chain from [InterleavingHistoryNode] to [ChainNode]  starting from the specified start index
     *
     * @return the first and the last nodes of the wrapped chain
     */
    private fun wrapChain(chain: List<InterleavingHistoryNode>, startIndex: Int = 0): Pair<ChainNode, ChainNode> {
        require(startIndex <= chain.lastIndex)

        val first = chain[startIndex]

        val root = ChainNode(first, mutableMapOf())
        var current = root

        for (i in startIndex + 1 until chain.size) {
            val next = chain[i]
            val nextNode = ChainNode(next, mutableMapOf())
            current.transitions[next.threadId] = nextNode
            current = nextNode
        }

        return root to current
    }

    /**
     * Location tracker inside a [InterleavingSequenceTrackableSet].
     * Consumes thread execution and switch events and answers if a current sequence corresponds to any cycle
     * is present inside the set or not.
     */
    internal inner class Cursor {
        /*
        The Cursor starts from the root of the tree and tries to find a way
         in that tree according to the execution and switch events.
        If no transition is possible, then the cursor becomes invalid
         and will never say that cycle is found until setTo or reset methods are called.

        Example of Cursor logic usage:
        Let's consider this events tree:
        root
        └── 1 : 2
            └── 0 : 6 [!]
                └── 1 : 4
                    └── 0 : 2 [!]
        First, `reset(threadId = 1)` method is called.
        Cursor will try to find a branch in from the root with threadId = 2.

        Then `onNextExecutionPoint()` method will be called twice and then `onNextSwitchPoint(0)` will be called once.
        All this time `isInCycle` property will return false, as the cursor goes from the first to the second node,
        and that doesn't correspond to a spin-loop events sequence.

        After the switch cursor will be located here:
        root
        └── 1 : 2
            └── 0 : 6 [!] <=== HERE (0/6 executions)
                └── 1 : 4
                    └── 0 : 2 [!]

        But we will run to the spin-loop only of 6 executions are performed in the thread with id 0.
        So during the next 5 `onNextExecutionPoint()` method calls `isInCycle` property will still return false.
        After the 6-th `onNextExecutionPoint()` method call `isInCycle` property will finally start returning true:
        root
        └── 1 : 2
            └── 0 : 6 [!] <=== HERE (6+/6 executions), we ran into the cycle!
                └── 1 : 4
                    └── 0 : 2 [!]

        Its worth noting that after doesn't matter how many times `onNextExecutionPoint()` is be invoked -
        `isInCycle` will be true, as in reality we still will be in the cycle.

        Only after `onNextSwitchPoint` call (or `reset` and `setTo`) returning value will become false.
        If the next thread is thread with id = 1, then the cursor will go the corresponding branch:
        root
        └── 1 : 2
            └── 0 : 6 [!]
                └── 1 : 4 <=== HERE (0/4 executions)
                    └── 0 : 2 [!]
        and will process it in the same way as described above.

        Otherwise, if the next thread id is, for example, 5, then the cursor will become invalid and
        `isInCycle` property will return false and won't react on any events until the next cursor `reset` or `setTo` methods calls.
         */

        /**
         *  Chain node where the cursor is currently located
         */
        private var currentChainNode: ChainNode? = null

        /**
         * Number of executions in the current thread
         */
        private var executionsCount = 0

        /**
         * Check if a current sequence corresponds to any cycle is present inside the set or not.
         */
        val isInCycle: Boolean
            get() = currentChainNode?.node?.let {
                it.cycleOccurred && executionsCount == it.executions
            } ?: false

        /**
         * Resets cursor to the leaf of the new added cycle
         */
        fun setTo(chainNode: ChainNode) {
            this.currentChainNode = chainNode
            executionsCount = chainNode.node.executions
        }

        /**
         * This method is called after every execution in the current thread.
         * Its worth noting that any new executions occurred when the cursor is in the cycle node will be ignored,
         * because after we ran into the cycle, it doesn't matter how many actions we will do - we will anyway stay in that cycle
         */
        fun onNextExecutionPoint(): Unit = ifValid { currentNode ->
            val node = currentNode.node
            // Ignore executions after we ran in the cycle
            if (node.cycleOccurred && executionsCount == node.executions) {
                return
            }

            // If we
            if (executionsCount < node.executions) {
                executionsCount++
                return
            }

            executionsCount = 0
            val nextChainNode = currentNode.transitions[node.threadId]
            if (nextChainNode == null) {
                invalidate()
                return
            }
            currentChainNode = nextChainNode
            onNextExecutionPoint()
        }

        /**
         * This method is called on each thread context switch point.
         *
         * @param threadId next thread id
         */
        fun onNextSwitchPoint(threadId: Int) = ifValid { bigNode ->
            val smallNode = bigNode.node

            if (executionsCount != smallNode.executions) {
                invalidate()
                return
            }

            executionsCount = 0

            val nextNode = bigNode.transitions[threadId]
            if (nextNode == null) {
                invalidate()
                return
            }
            currentChainNode = nextNode
        }

        /**
         * Reset the cursor from the start of the current tree.
         *
         * @param threadId id of the start thread
         */
        fun reset(threadId: Int) {
            currentChainNode = rootTransitions[threadId]
            executionsCount = 0
        }

        private inline fun ifValid(action: (ChainNode) -> Unit) {
            action(currentChainNode ?: return)
        }

        private fun invalidate() {
            currentChainNode = null
        }
    }

}
