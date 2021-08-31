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
import java.lang.Error
import java.lang.Integer.min
/*
data class InterleavingTreeNode(
    val id: Int, val context: ModelCheckingContext<*, *>,
    val task: Task,
    val parent: InterleavingTreeNode? = null,
    val numberOfFailures: Int = 0
) {
    val nodeId = context.interleavingTreeNodeCnt++
    val taskToMessageIds = mutableMapOf<Int, Int>()
    val nextPossibleTasksIds = mutableListOf<Int>()
    val children = mutableMapOf<Int, InterleavingTreeNode>()
    var isFullyExplored = false
    var isVisited = false
    var fractionUnexplored = 1.0
    var minDistance = Int.MAX_VALUE
    //val notCheckedTasks = mutableListOf<Int>()
    //val allNotChecked = mutableListOf<Int>()

    fun minDistance() = if (minDistance == Int.MAX_VALUE) minDistance - 1 else minDistance

    operator fun get(i: Int): InterleavingTreeNode? {
        if (i !in nextPossibleTasksIds && i !in taskToMessageIds) return null
        if (i !in context.runner.tasks) {
            check(nextPossibleTasksIds.isEmpty())
        }
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
        val tasksToCheck = if (task !is NodeCrashTask) currentTasks.filter {
            it.key != id && (it.key > id || it.value.iNode == currentTask.iNode)
        } else {
            currentTasks.filter { it.key != id }
        }
        nextPossibleTasksIds.addAll(if (context.testCfg.messageOrder == MessageOrder.FIFO) tasksToCheck.filter { t ->
            !currentTasks.any {
                it.key != id && it != t && it.value.iNode == t.value.iNode && it.value.clock.happensBefore(
                    t.value.clock
                )
            }
        }.map { it.key } else tasksToCheck.map { it.key })
        //notCheckedTasks.addAll(currentTasks.filter { it.key !in tasksToCheck }.map { it.key })
        //allNotChecked.addAll(currentTasks.filter { it.key !in nextPossibleTasksIds }.map { it.key })
        isVisited = true
        if (nodeId == 6778L && taskToMessageIds.size == 2 && nextPossibleTasksIds.isEmpty()) {
            8 + 8
        }
    }

    fun next() = nextPossibleTasksIds.minOrNull() ?: if (isVisited && !isFull) taskToMessageIds.minOfOrNull { it.key } else null

    fun chooseNextInterleaving(builder: InterleavingTreeBuilder): Interleaving {
        val next = next() ?: return builder.build()
        if (next !in children) {
            builder.addNextTransition(NextLeafPoint(next))
            return builder.build()
        }
        val unexploredTasks = if (isFull) {
            nextPossibleTasksIds.filter { it !in children }
        } else {
            (nextPossibleTasksIds + taskToMessageIds.keys).filter { it !in children }
        }
        if (unexploredTasks.isNotEmpty()) {
            val r = unexploredTasks.random(context.generatingRandom)
            builder.addNextTransition(SwitchLeafPoint(r))
            return builder.build()
        }
        val possibleMoves =
            children.filter { it.value.minDistance + 1 <= builder.remainedSwitches() || it.key == next && it.value.minDistance <= builder.remainedSwitches() }
        val total = possibleMoves.values.sumByDouble { it.fractionUnexplored }
        val random = context.generatingRandom.nextDouble() * total
        var sumWeight = 0.0
        possibleMoves.forEach { choice ->
            sumWeight += choice.value.fractionUnexplored
            if (sumWeight >= random) {
                builder.addNextTransition(
                    if (choice.key != next) {
                        SwitchChildPoint(choice.key)
                    } else {
                        NextChildPoint(choice.key)
                    }
                )
                return choice.value.chooseNextInterleaving(builder)
            }
        }
        val choice = possibleMoves.entries.last { !it.value.isFullyExplored }
        builder.addNextTransition(
            if (choice.key != next) {
                SwitchChildPoint(choice.key)
            } else {
                NextChildPoint(choice.key)
            }
        )
        return choice.value.chooseNextInterleaving(builder)
    }

    val isFull = numberOfFailures == context.nodeCrashInfo.maxNumberOfFailedNodes

    fun updateStats() {
        if (nextPossibleTasksIds.isEmpty() && (taskToMessageIds.isEmpty()
                    || isFull)
        ) {
            isFullyExplored = true
            fractionUnexplored = 0.0
            minDistance = Int.MAX_VALUE
            return
        }
        val possibleFailuresCount = if (isFull) {
            0
        } else {
            taskToMessageIds.keys.filter { it !in children }.size
        }
        val total = children.values.fold(0.0) { acc, choice ->
            acc + choice.fractionUnexplored
        } + nextPossibleTasksIds.filter { it !in children }.size + possibleFailuresCount
        fractionUnexplored = total / (nextPossibleTasksIds.size + possibleFailuresCount)
        isFullyExplored =
            nextPossibleTasksIds.none { it !in children } && (possibleFailuresCount == 0 || taskToMessageIds.keys.none { it !in children }) && children.values.all { it.isFullyExplored }
        val next = next()
        if (next != 0 && next !in children) {
            minDistance = 0
            return
        }
        if (nextPossibleTasksIds.any { it !in children } || possibleFailuresCount != 0) {
            minDistance = 1
            return
        }
        minDistance =
            min(children.filter { it.key != next }.minOfOrNull { it.value.minDistance() + 1 } ?: Int.MAX_VALUE,
                children[next]?.minDistance ?: Int.MAX_VALUE)
    }
}


/*
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
 */
sealed class SwitchPoint {
    abstract val point : Int
    abstract val isSwitch: Boolean
}

data class NextChildPoint(override val point: Int, override val isSwitch: Boolean = false) : SwitchPoint()
data class NextLeafPoint(override val point: Int, override val isSwitch: Boolean = false) : SwitchPoint()
data class SwitchChildPoint(override val point: Int, override val isSwitch: Boolean = true) : SwitchPoint()
data class SwitchLeafPoint(override val point: Int, override val isSwitch: Boolean = true) : SwitchPoint()

class Interleaving(val path: List<SwitchPoint>)

class InterleavingTreeBuilder(
    val switchLimit: Int
) {
    var currentSwitchCount: Int = 0
    val path = mutableListOf<SwitchPoint>()

    fun build() = Interleaving(path)

    fun addNextTransition(point: SwitchPoint) {
        path.add(point)
        if (point.isSwitch) currentSwitchCount++
    }

    fun remainedSwitches() = switchLimit - currentSwitchCount
}
*/