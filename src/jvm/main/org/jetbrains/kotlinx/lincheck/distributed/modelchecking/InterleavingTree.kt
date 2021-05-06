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

package org.jetbrains.kotlinx.lincheck.distributed.modelchecking

/**
 * An abstract node with an execution choice in the interleaving tree.
 */
class InterleavingTreeNode(
    val context: ModelCheckingContext<*, *>,
    val taskId: Int,
    val clock: VectorClock, val iNode: Int,
    val choices: MutableList<InterleavingTreeNode> = mutableListOf<InterleavingTreeNode>()
) {
    private var fractionUnexplored = 1.0

    var isFullyExplored: Boolean = false
        protected set

    var isExplored = false

    var curChild = 0

    val pendingChoices = mutableListOf<InterleavingTreeNode>()

    fun nextInterleaving(): Interleaving? {
        // Check if everything is fully explored and there are no possible interleavings with more switches.
        if (isFullyExplored) return null
        return nextInterleaving(InterleavingBuilder())
    }

    fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving {
        val next = chooseUnexploredNode()
        interleavingBuilder.addNode(next.iNode)
        return next.nextInterleaving(interleavingBuilder)
    }

    protected fun resetExploration() {
        curChild = 0
        choices.forEach { it.resetExploration() }
        updateExplorationStatistics()
    }

    fun finishExploration() {
        isFullyExplored = true
        fractionUnexplored = 0.0
    }

    fun updateExplorationStatistics() {
        if (choices.isEmpty()) {
            finishExploration()
            return
        }
        val total = choices.fold(0.0) { acc, choice ->
            acc + choice.fractionUnexplored
        }
        fractionUnexplored = total / choices.size
        isFullyExplored = choices.all { it.isFullyExplored }
    }

    private fun chooseBestNode(nodes: List<InterleavingTreeNode>): InterleavingTreeNode? {
        val total = nodes.sumByDouble { it.fractionUnexplored }
        val random = context.generatingRandom.nextDouble() * total
        var sumWeight = 0.0
        nodes.forEach { choice ->
            sumWeight += choice.fractionUnexplored
            if (sumWeight >= random)
                return choice
        }
        return choices.lastOrNull { !it.isFullyExplored }
    }

    protected fun chooseUnexploredNode(): InterleavingTreeNode {
        if (choices.size == 1) return choices.first()
        val nodesToCheck = choices.filter { it.iNode >= iNode || clock.happensBefore(it.clock) }
        return chooseBestNode(nodesToCheck) ?: chooseBestNode(choices)!!
    }

    fun hasNext() = choices.isNotEmpty()

    fun addChoice(clock: VectorClock, iNode: Int): Int {
        if (isExplored) {
            val treeNode = choices[curChild]
            check(treeNode.clock == clock && treeNode.iNode == iNode)
            curChild++
            return treeNode.taskId
        }
        val newId = context.tasksId++
        val newNode = InterleavingTreeNode(context, newId, clock, iNode)
        pendingChoices.add(newNode)
        return newId
    }

    fun finish() {
        choices.addAll(pendingChoices)
        for (choice in pendingChoices) {
            val newChoices = choices.filter { it.taskId != choice.taskId }.map { it.copy() }
            choice.choices.addAll(newChoices)
        }
        pendingChoices.clear()
        isExplored = true
    }

    fun copy() = InterleavingTreeNode(
        context, taskId, clock.copy(
            clock =
            IntArray(clock.nodes)
        ), iNode, choices.toMutableList()
    )

    fun addNewChoice(newNode: InterleavingTreeNode) {
        choices.forEach { it.addNewChoice(newNode) }
        choices.add(newNode.copy())
    }

    fun next() = if (choices.isNotEmpty()) {
        chooseUnexploredNode()
    } else {
        null
    }

    operator fun get(taskId: Int): InterleavingTreeNode {
        return choices.last { it.taskId == taskId }
    }
}

class Interleaving(val choices: List<Int>)

class InterleavingBuilder {
    private val path = mutableListOf<Int>()

    fun addNode(i: Int) {
        path.add(i)
    }

    fun build() = Interleaving(path)
}

