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
    var minDistance = Int.MAX_VALUE
    val notCheckedTasks = mutableListOf<Int>()
    val allNotChecked = mutableListOf<Int>()

    fun minDistance() = if (minDistance == Int.MAX_VALUE) minDistance - 1 else minDistance

    operator fun get(i: Int): InterleavingTreeNode? {
        if (i !in nextPossibleTasksIds && i !in taskToMessageIds) return null
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
        notCheckedTasks.addAll(currentTasks.filter { it.key !in tasksToCheck }.map { it.key })
        allNotChecked.addAll(currentTasks.filter { it.key !in nextPossibleTasksIds }.map { it.key })
        isVisited = true
    }

    fun next() = nextPossibleTasksIds.minOrNull()

    fun chooseNextInterleaving(builder: InterleavingTreeBuilder): Interleaving {
        /*if (builder.isFull()) {
            allInversionsUsed = true
            val next = next() ?: return builder.build()
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
                builder.numberOfInversions++
                builder.addNextTransition(next)
                return builder.build()
            }
        }
        if (!builder.areFailuresFull()) {
            val notTriedFailures = taskToMessageIds.keys.filterNot { it in children }
            if (notTriedFailures.isNotEmpty()) {
                val next = notTriedFailures.random(context.generatingRandom)
                builder.numberOfFailures++
                builder.addNextTransition(next)
                return builder.build()
            }
        }
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
        if (choice.key in taskToMessageIds) {
            builder.numberOfFailures++
        } else {
            if (choice.key != next()) builder.numberOfInversions++
        }
        return choice.value.chooseNextInterleaving(builder)*/
        val next = next() ?: return builder.build()
        if (next !in children) {
            builder.addNextTransition(next)
            //println("id=$id, next=$next, limit=${builder.switchLimit}, remained=${builder.remainedSwitches()}")
            return builder.build()
        }
        val unexploredTasks = nextPossibleTasksIds.filter { it !in children }
        if (unexploredTasks.isNotEmpty()) {
            val r = unexploredTasks.random(context.generatingRandom)
            builder.currentSwitchCount++
            //println("id=$id, switch=$r, limit=${builder.switchLimit}, remained=${builder.remainedSwitches()}")
            builder.addNextTransition(r)
            return builder.build()
        }
        val possibleMoves =
            children.filter { it.value.minDistance + 1 <= builder.remainedSwitches() || it.key == next && it.value.minDistance <= builder.remainedSwitches() }
        val total = possibleMoves.values.sumByDouble { it.fractionUnexplored }
        //println("Moves id=$id minDist=$minDistance switches=${builder.remainedSwitches()} ${children.map { it.key to it.value.minDistance }}")
        val random = context.generatingRandom.nextDouble() * total
        var sumWeight = 0.0
        possibleMoves.forEach { choice ->
            sumWeight += choice.value.fractionUnexplored
            if (sumWeight >= random) {
                builder.addNextTransition(choice.key)
                if (choice.key != next) builder.currentSwitchCount++
                return choice.value.chooseNextInterleaving(builder)
            }
        }
        val choice = possibleMoves.entries.last { !it.value.isFullyExplored }
        builder.addNextTransition(choice.key)
        if (choice.key != next) builder.currentSwitchCount++
        return choice.value.chooseNextInterleaving(builder)
    }

    fun updateStats() {
        if (nextPossibleTasksIds.isEmpty()) {
            isFullyExplored = true
            fractionUnexplored = 0.0
            minDistance = Int.MAX_VALUE
            return
        }
        val total = children.values.fold(0.0) { acc, choice ->
            acc + choice.fractionUnexplored
        } + nextPossibleTasksIds.filter { it !in children }.size
        fractionUnexplored = total / nextPossibleTasksIds.size
        //println("id=$id, fractionUnexplored=$fractionUnexplored")
        isFullyExplored = nextPossibleTasksIds.none { it !in children } && children.values.all { it.isFullyExplored }
        val next = next()!!
        if (next !in children) {
            minDistance = 0
            return
        }
        if (nextPossibleTasksIds.any { it !in children }) {
            minDistance = 1
            return
        }
        minDistance =
            min(children.filter { it.key != next }.minOfOrNull { it.value.minDistance() + 1 } ?: Int.MAX_VALUE,
                children[next]!!.minDistance)
    }
}

class Interleaving(val path: List<Int>)

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

class InterleavingTreeBuilder(
    val switchLimit: Int
) {
    var currentSwitchCount: Int = 0
    val path = mutableListOf<Int>()

    fun build() = Interleaving(path)

    fun addNextTransition(i: Int) {
        path.add(i)
    }

    fun remainedSwitches() = switchLimit - currentSwitchCount
}
