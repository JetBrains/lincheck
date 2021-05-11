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

import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder

/**
 * An abstract node with an execution choice in the interleaving tree.
 */
/*
class InterleavingTreeNode(
    val context: ModelCheckingContext<*, *>,
    val taskId: Int,
    val clock: VectorClock, val iNode: Int,
    val choices: MutableList<InterleavingTreeNode> = mutableListOf()
) {
    var fractionUnexplored = 1.0
        private set

    var isFullyExplored: Boolean = false
        protected set

    var isExplored = false

    var curChild = 0

    var childrenFirstIndex = 0

    val pendingChoices = mutableListOf<InterleavingTreeNode>()

    fun nextInterleaving(): Interleaving? {
        // Check if everything is fully explored and there are no possible interleavings with more switches.
        if (isFullyExplored) return null
        return nextInterleaving(InterleavingBuilder())
    }

    fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving {
        val next = chooseUnexploredNode()
        interleavingBuilder.addNode(next!!.iNode)
        return next.nextInterleaving(interleavingBuilder)
    }

    fun resetExploration() {
        curChild = 0
        choices.forEach { it.resetExploration() }
        updateExplorationStatistics()
    }

    fun finishExploration() {
        isFullyExplored = true
        fractionUnexplored = 0.0
    }

    fun updateExplorationStatistics() {
        curChild = 0
        val nodesToCheck = nodesToCheck()
        if (nodesToCheck.isEmpty()) {
            if (isExplored) finishExploration()
            return
        }
        val total = nodesToCheck.fold(0.0) { acc, choice ->
            acc + choice.fractionUnexplored
        }
        fractionUnexplored = total / nodesToCheck.size
        isFullyExplored = nodesToCheck.all { it.isFullyExplored }
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
        return nodes.lastOrNull { !it.isFullyExplored }
    }

    fun nodesToCheck() = filterNonFifo().filter { it.iNode >= iNode || clock.happensBefore(it.clock) }

    protected fun chooseUnexploredNode(): InterleavingTreeNode? {
        val choices = filterNonFifo()
        if (choices.size == 1) return choices.first()
        val nodesToCheck = nodesToCheck()
        debugLogs.add("Node=$iNode, taskId=$taskId, clock=${clock}")
        choices.forEach {
            debugLogs.add(
                "Child iNode=${it.iNode} clock=${it.clock} taskId=${it.taskId} happensBefore=${
                    clock.happensBefore(
                        it.clock
                    )
                } ourClock=${clock}"
            )
        }
        choices.filter { it !in nodesToCheck }.forEach {
            debugLogs.add(
                "Skipped child iNode=${it.iNode} clock=${it.clock} taskId=${it.taskId} happensBefore=${
                    clock.happensBefore(it.clock)
                } ourClock=${clock}"
            )
        }
        return chooseBestNode(nodesToCheck) ?: chooseBestNode(choices)
    }

    fun hasNext() = choices.isNotEmpty()

    fun addChoice(clock: VectorClock, iNode: Int): Int {
        if (isExplored) {
            val treeNode = choices[curChild + childrenFirstIndex]
            check(treeNode.clock == clock && treeNode.iNode == iNode) {

            }
            curChild++
            return treeNode.taskId
        }
        val newId = ++(context.tasksId)
        val newNode = InterleavingTreeNode(context, newId, clock, iNode)
        pendingChoices.add(newNode)
        return newId
    }

    fun filterNonFifo() = if (context.testCfg.messageOrder == MessageOrder.FIFO) {
        choices.filter { c ->
            !choices.any {
                it != c && it.iNode == c.iNode && it.clock.happensBefore(
                    c.clock
                )
            }
        }
    } else {
        choices
    }

    fun finish() {
        if (isExplored) {
            check(pendingChoices.isEmpty())
            return
        }
        childrenFirstIndex = choices.size
        //println("New choices for task $taskId")
        //pendingChoices.forEach { println("Pending task ${it.taskId}") }
        //choices.forEach { i -> i.choices.addAll(pendingChoices.map { it.copy() }) }
        choices.addAll(pendingChoices)
        for (choice in choices) {
            val newChoices = choices.filter { it.taskId != choice.taskId }.map { it.copy() }
            choice.choices.addAll(newChoices)
        }
        pendingChoices.clear()
        isExplored = true
    }

    fun copy() = InterleavingTreeNode(
        context, taskId, clock.copy(
            clock =
            clock.clock.copyOf()
        ), iNode
    )

    fun addNewChoice(newNode: InterleavingTreeNode) {
        choices.forEach { it.addNewChoice(newNode) }
        choices.add(newNode.copy())
    }

    fun next() = chooseUnexploredNode()

    operator fun get(taskId: Int): InterleavingTreeNode {
        return choices.last { it.taskId == taskId }
    }
}
*/

//class Interleaving(val choices: List<Inter>)

class InterleavingBuilder {
    private val path = mutableListOf<InterleavingTreeNode>()

    /*fun addNode(i: Int) {
        path.add(i)
    }

    fun build() = Interleaving(path)*/
}

data class InterleavingTreeNode(
    val id: Int, val context: ModelCheckingContext<*, *>,
    val task: Task,
    val parent: InterleavingTreeNode? = null,
    val numberOfFailures: Int = 0
) {
    val taskToMessageIds = mutableMapOf<Int, Int>()
    val nextPossibleTasksIds = mutableListOf<Int>()
    val children = mutableMapOf<Int, InterleavingTreeNode>()
    var isFullyExplored = false
    var isVisited = false
    var fractionUnexplored = 1.0
    var allInversionsUsed = false
    val notCheckedTasks = mutableListOf<Int>()
    val allNotChecked = mutableListOf<Int>()
    val operations = mutableMapOf<Int, Int>()

    fun isExploredNow(): Boolean = allInversionsUsed
            || nextPossibleTasksIds.all { it in children } && children.all { it.value.isExploredNow() }

    operator fun get(i: Int): InterleavingTreeNode? {
        if (i !in nextPossibleTasksIds || i !in taskToMessageIds) return null
        val task = context.runner.tasks[i]!!
        val newNumberOfFailures = if (i in taskToMessageIds) numberOfFailures + 1 else numberOfFailures
        return children.getOrPut(i) { InterleavingTreeNode(i, context, task, this, newNumberOfFailures) }
    }

    fun addMessage(msgId: Int, taskId: Int) {
        if (isVisited) return
        taskToMessageIds[taskId] = msgId
    }

    fun finish(currentTasks: Map<Int, Task>) {
        if (isVisited) return
        check(nextPossibleTasksIds.isEmpty())
        val currentTask = currentTasks[id]!!
        currentTasks.filter { it.value is OperationTask }.forEach { operations[it.key] = it.value.iNode }
        val tasksToCheck = currentTasks.filter {
            it.key != id && currentTask.goesBefore(it.value)
        }
        //println("Tasks to check $tasksToCheck")
        nextPossibleTasksIds.addAll(if (context.testCfg.messageOrder == MessageOrder.FIFO) tasksToCheck.filter { t ->
            !currentTasks.any {
                it.key != id && it != t && it.value.iNode == t.value.iNode && it.value.clock.happensBefore(
                    t.value.clock
                )
            }
        }.map { it.key } else tasksToCheck.map { it.key })
        notCheckedTasks.addAll(currentTasks.filter { it.key !in tasksToCheck }.map { it.key })
        allNotChecked.addAll(currentTasks.filter { it.key !in nextPossibleTasksIds }.map { it.key })
        //println("Next possible tasks ${id} ${nextPossibleTasksIds}")
        isVisited = true
    }

    fun next() = nextPossibleTasksIds.filter { it !in operations }.minOrNull() ?: nextPossibleTasksIds.minByOrNull { operations[it]!! }

    fun nextNode(): InterleavingTreeNode? {
        val next = next() ?: return null
        return get(next)
    }

    fun chooseNextInterleaving(builder: InterleavingTreeBuilder): Interleaving {
        //println("Choose next interleaving $id ${builder.path}")
        if (builder.isFull()) {
            allInversionsUsed = true
            val next = next() ?: return builder.build()
            //println("Next in path $next")
            builder.addNextTransition(next)
            return if (next !in children) {
                builder.build()
            } else {
                children[next]!!.chooseNextInterleaving(builder)
            }
        }
        if (!builder.areInversionsFull()) {
            val notTriedInversions = nextPossibleTasksIds.filterNot { it in children }
            if (notTriedInversions.isNotEmpty()) {
                val next = notTriedInversions.random(context.generatingRandom)
                //println("Add inversion")
                builder.numberOfInversions++
                builder.addNextTransition(next)
                return builder.build()
            } else {
                val total = children.values.sumByDouble { it.fractionUnexplored }
                val random = context.generatingRandom.nextDouble() * total
                var sumWeight = 0.0
                children.forEach { choice ->
                    sumWeight += choice.value.fractionUnexplored
                    if (sumWeight >= random) {
                        builder.addNextTransition(choice.key)
                        if (choice.key != next()) builder.numberOfInversions++
                        return choice.value.chooseNextInterleaving(builder)
                    }
                }
                val choice = children.entries.lastOrNull { !it.value.isFullyExplored } ?: return builder.build()
                builder.addNextTransition(choice.key)
                if (choice.key != next()) builder.numberOfInversions++
                return choice.value.chooseNextInterleaving(builder)
            }
        }
        return children[next()]?.chooseNextInterleaving(builder) ?: builder.build()
        //if (!builder.areFailuresFull())
        //if (!)
    }

    fun updateStats() {
        if (nextPossibleTasksIds.isEmpty()) {
            isFullyExplored = true
            fractionUnexplored = 0.0
        }
        val total = children.values.fold(0.0) { acc, choice ->
            acc + choice.fractionUnexplored
        } + nextPossibleTasksIds.filter { it !in children }.size
        fractionUnexplored = total / nextPossibleTasksIds.size
        isFullyExplored = nextPossibleTasksIds.none { it !in children } && children.values.all { it.isFullyExplored }
    }

    //fun goTo(position: Int)
}

class Interleaving(val path: List<Int>)

class InterleavingTreeBuilder(
    val maxNumberOfInversions: Int,
    val maxNumberOfFailures: Int,
) {
    var numberOfInversions: Int = 0
    var numberOfFailures: Int = 0
    val path = mutableListOf<Int>()
    fun isFull() = maxNumberOfFailures == numberOfFailures && maxNumberOfInversions == numberOfInversions
    fun areFailuresFull() = maxNumberOfFailures == numberOfFailures

    fun areInversionsFull() = maxNumberOfInversions == numberOfFailures

    fun build() = Interleaving(path)

    fun addNextTransition(i: Int) {
        path.add(i)
    }
}

