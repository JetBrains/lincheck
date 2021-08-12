/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking

import org.jetbrains.kotlinx.lincheck.runner.InvocationResult

private typealias InterleavingTreeNode = AbstractModelCheckingStrategy<*, *>.InterleavingTreeNode

internal abstract class InterleavingTreeExplorer<BUILDER : AbstractModelCheckingStrategy<*, BUILDER>.InterleavingBuilder<*>>(
    val root: AbstractModelCheckingStrategy<*, BUILDER>.InterleavingTreeNode
) {
    abstract fun hasNextInterleaving(): Boolean
    abstract fun runNextInterleaving(): InvocationResult
    abstract fun AbstractModelCheckingStrategy<*, BUILDER>.InterleavingTreeNode.onNodeEntering(builder: BUILDER)
    abstract fun InterleavingTreeNode.onNodeLeaving()
    protected abstract fun InterleavingTreeNode.updateExplorationStatistics()
}

// TODO: think of better names
enum class ExplorationTactic {
    MINIMIZE_DEPTH,
    DESCEND_RANDOMLY,
    DESCEND_DEEPER
}

internal fun <BUILDER : AbstractModelCheckingStrategy<*, BUILDER>.InterleavingBuilder<*>> ExplorationTactic.toInterleavingTreeExplorer(
    root: AbstractModelCheckingStrategy<*, BUILDER>.InterleavingTreeNode
): InterleavingTreeExplorer<BUILDER> = when (this) {
    ExplorationTactic.MINIMIZE_DEPTH -> DepthMinimizingInterleavingTreeExplorer(root)
    ExplorationTactic.DESCEND_RANDOMLY -> RandomDescendingInterleavingTreeExplorer(root)
    ExplorationTactic.DESCEND_DEEPER -> DeeperDescendingInterleavingTreeExplorer(root)
}

internal class DepthMinimizingInterleavingTreeExplorer<BUILDER : AbstractModelCheckingStrategy<*, BUILDER>.InterleavingBuilder<*>>(
    root: AbstractModelCheckingStrategy<*, BUILDER>.InterleavingTreeNode
) : InterleavingTreeExplorer<BUILDER>(root) {
    var maxNumberOfEvents = 1
    var currentEvents = 0

    override fun hasNextInterleaving(): Boolean {
        if (root.isFullyExplored) {
            // Increase the maximum number of switches or crashes that can be used,
            // because there are no more not covered interleavings
            // with the previous maximum number of switches.
            maxNumberOfEvents++
            root.resetExploration(0)
        }
        // Check if everything is fully explored and there are no possible interleavings with more switches.
        return !root.isFullyExplored
    }

    private fun InterleavingTreeNode.resetExploration(parentDepth: Int) {
        if (!isInitialized) {
            // This node is currently a non-initialized leaf
            isFullyExplored = false
            fractionUnexplored = 1.0
            return
        }
        val nodeDepth = parentDepth + if (needsExploration) 1 else 0
        choices.forEach { it.node.resetExploration(nodeDepth) }
        currentEvents = nodeDepth
        updateExplorationStatistics()
    }

    override fun runNextInterleaving(): InvocationResult {
        check(currentEvents == 0)
        return root.runNextInterleaving()
    }

    override fun AbstractModelCheckingStrategy<*, BUILDER>.InterleavingTreeNode.onNodeEntering(builder: BUILDER) {
        // Count only those events that require an invocation to determine node's choices.
        if (needsExploration) currentEvents++
    }

    override fun InterleavingTreeNode.onNodeLeaving() {
        updateExplorationStatistics()
        if (needsExploration) currentEvents--
    }

    override fun InterleavingTreeNode.updateExplorationStatistics() {
        check(isInitialized) {
            "An interleaving tree node was not initialized properly. " +
                    "Probably caused by non-deterministic behaviour (WeakHashMap, Object.hashCode, etc)"
        }
        if (choices.isEmpty() || currentEvents == maxNumberOfEvents) {
            // This node is currently an initialized leaf
            isFullyExplored = true
            fractionUnexplored = 0.0
            return
        }
        val total = choices.fold(0.0) { acc, choice ->
            acc + choice.node.fractionUnexplored
        }
        fractionUnexplored = total / choices.size
        isFullyExplored = choices.all { it.node.isFullyExplored }
    }
}

// Decrease in sqrt(2) times per layer, essentially resulting in decrease in 2 times per level (2 layers per level).
private const val WEIGHT_DECREASING_RATIO = 1.41421356237

internal class RandomDescendingInterleavingTreeExplorer<BUILDER : AbstractModelCheckingStrategy<*, BUILDER>.InterleavingBuilder<*>>(
    root: AbstractModelCheckingStrategy<*, BUILDER>.InterleavingTreeNode
) : InterleavingTreeExplorer<BUILDER>(root) {
    override fun hasNextInterleaving(): Boolean = !root.isFullyExplored

    override fun runNextInterleaving(): InvocationResult = root.runNextInterleaving()

    override fun AbstractModelCheckingStrategy<*, BUILDER>.InterleavingTreeNode.onNodeEntering(builder: BUILDER) {
        // do nothing
    }

    override fun InterleavingTreeNode.onNodeLeaving() {
        updateExplorationStatistics()
    }

    override fun InterleavingTreeNode.updateExplorationStatistics() {
        check(isInitialized) {
            "An interleaving tree node was not initialized properly. " +
                    "Probably caused by non-deterministic behaviour (WeakHashMap, Object.hashCode, etc)"
        }
        if (choices.isEmpty()) {
            // This node is currently an initialized leaf
            isFullyExplored = true
            fractionUnexplored = 0.0
            return
        }
        val total = choices.fold(0.0) { acc, choice ->
            acc + choice.node.fractionUnexplored
        }
        fractionUnexplored = total / choices.size / WEIGHT_DECREASING_RATIO
        isFullyExplored = choices.all { it.node.isFullyExplored }
    }
}

internal class DeeperDescendingInterleavingTreeExplorer<BUILDER : AbstractModelCheckingStrategy<*, BUILDER>.InterleavingBuilder<*>>(
    root: AbstractModelCheckingStrategy<*, BUILDER>.InterleavingTreeNode
) : InterleavingTreeExplorer<BUILDER>(root) {
    var lastBuilder: BUILDER? = null
    var nodes: MutableList<AbstractModelCheckingStrategy<*, BUILDER>.InterleavingTreeNode> = mutableListOf()
    var maxNumberOfEvents = 1
    var currentEvents = 0

    override fun hasNextInterleaving(): Boolean {
        if (root.isFullyExplored) {
            // Increase the maximum number of switches or crashes that can be used,
            // because there are no more not covered interleavings
            // with the previous maximum number of switches.
            maxNumberOfEvents++
            root.resetExploration(0)
        }
        // Check if everything is fully explored and there are no possible interleavings with more switches.
        return !root.isFullyExplored
    }

    private fun InterleavingTreeNode.resetExploration(parentDepth: Int) {
        if (!isInitialized) {
            // This node is currently a non-initialized leaf
            isFullyExplored = false
            fractionUnexplored = 1.0
            return
        }
        val nodeDepth = parentDepth + if (needsExploration) 1 else 0
        choices.forEach { it.node.resetExploration(nodeDepth) }
        currentEvents = nodeDepth
        updateExplorationStatistics()
    }

    override fun runNextInterleaving(): InvocationResult {
        currentEvents = 0
        for (node in nodes)
            if (node.needsExploration) currentEvents++
        if (currentEvents >= maxNumberOfEvents * 2 || nodes.lastOrNull()?.choices?.all { it.node.isFullyExplored } != false) {
            currentEvents = 0
            nodes.clear()
            lastBuilder = null
            return root.runNextInterleaving()
        } else {
            val nextChoice = nodes.last().chooseUnexploredNode()
            nodes.last().run { lastBuilder!!.applyChoice(nextChoice.value) }
            val result = nextChoice.node.runNextInterleaving(lastBuilder!!)
            for (node in nodes.reversed()) {
                // Calculate depth of the current node
                var curEvents = 0
                for (n in nodes) {
                    if (n.needsExploration) curEvents++
                    if (n == node) break
                }
                val previousCurrentEvents = currentEvents
                // Update node weight
                currentEvents = curEvents
                node.updateExplorationStatistics()
                // Rollback current events count
                currentEvents = previousCurrentEvents
            }
            return result
        }
    }

    override fun AbstractModelCheckingStrategy<*, BUILDER>.InterleavingTreeNode.onNodeEntering(builder: BUILDER) {
        // Save the last node and builder
        lastBuilder = builder
        nodes.add(this)
        // Count only those events that require an invocation to determine node's choices.
        if (needsExploration) currentEvents++
    }

    override fun InterleavingTreeNode.onNodeLeaving() {
        updateExplorationStatistics()
        if (needsExploration) currentEvents--
    }

    override fun InterleavingTreeNode.updateExplorationStatistics() {
        check(isInitialized) {
            "An interleaving tree node was not initialized properly. " +
                    "Probably caused by non-deterministic behaviour (WeakHashMap, Object.hashCode, etc)"
        }
        if (choices.isEmpty() || (currentEvents == maxNumberOfEvents && needsExploration)) {
            // This node is currently an initialized leaf
            isFullyExplored = true
            fractionUnexplored = 0.0
            return
        }
        val total = choices.fold(0.0) { acc, choice ->
            acc + choice.node.fractionUnexplored
        }
        fractionUnexplored = total / choices.size
        isFullyExplored = choices.all { it.node.isFullyExplored }
    }
}