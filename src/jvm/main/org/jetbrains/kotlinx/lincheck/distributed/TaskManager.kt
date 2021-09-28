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

package org.jetbrains.kotlinx.lincheck.distributed

import java.util.*

sealed class Task {
    abstract val iNode: Int
    abstract val f: suspend () -> Unit
}

sealed class TimeTask : Task() {
    abstract var ticks: Int
}

data class MessageReceiveTask(
    override val iNode: Int,
    val from: Int,
    override val f: suspend () -> Unit
) : Task()

data class OperationTask(
    override val iNode: Int,
    override val f: suspend () -> Unit
) : Task()

data class NodeRecoverTask(
    override val iNode: Int,
    override val f: suspend () -> Unit
) : Task()

data class Timeout(override var ticks: Int, override val iNode: Int, override val f: suspend () -> Unit) : TimeTask()
data class Timer(override var ticks: Int, override val iNode: Int, override val f: suspend () -> Unit) : TimeTask()

internal abstract class TaskManager {
    var counter = 0
    var currentTaskId = -1
    val timers = mutableMapOf<Int, TimeTask>()

    protected fun getId(initial: Int): Int {
        var id = initial
        while (id == currentTaskId || id in timers || taskWithIdExists(id)) id++
        return id
    }

    protected abstract fun taskWithIdExists(id: Int): Boolean

    fun addTimeTask(task: TimeTask) {
        val id = getId(counter + task.ticks - 1)
        timers[id] = task
    }

    abstract fun addTask(task: Task)

    abstract fun getTaskById(taskId: Int): Task?

    open fun removeAllForNode(iNode: Int) {
        val timersToRemove = timers.filter { it.value.iNode == iNode }
        timersToRemove.forEach { timers.remove(it.key) }
    }

    abstract fun getAvailableTasks(): Map<Int, Task>
}

internal class NoFifoTaskManager : TaskManager() {
    private val tasks = mutableMapOf<Int, Task>()
    override fun getAvailableTasks(): Map<Int, Task> {
        if (tasks.isEmpty()) {
            val minValue = timers.minOfOrNull { it.value.ticks }
            val nextTimers = timers.filter { it.value.ticks == minValue }
            nextTimers.forEach { (t, u) ->
                check(t !in tasks)
                tasks[t] = u
                timers.remove(t)
            }
        }
        return tasks
    }

    override fun taskWithIdExists(id: Int): Boolean = id in tasks

    override fun addTask(task: Task) {
        val taskId = getId(counter)
        counter = taskId + 1
        check(taskId !in tasks)
        tasks[taskId] = task
    }

    override fun getTaskById(taskId: Int): Task? {
        timers.forEach { (_, u) -> u.ticks-- }
        val timeTaskToMove = timers.filter { it.value.ticks <= 0 }
        timeTaskToMove.forEach { (t, u) ->
            check(t !in tasks)
            tasks[t] = u
        }
        timeTaskToMove.forEach { (t, _) -> timers.remove(t) }
        currentTaskId = taskId
        return tasks.remove(taskId)
    }

    override fun removeAllForNode(iNode: Int) {
        super.removeAllForNode(iNode)
        val tasksToRemove = tasks.filter { it.value.iNode == iNode }
        tasksToRemove.forEach { (t, _) -> tasks.remove(t) }
    }
}

internal class FifoTaskManager : TaskManager() {
    private val tasks = mutableListOf<Queue<Pair<Int, Task>>>()
    private val taskIds = mutableSetOf<Int>()

    override fun taskWithIdExists(id: Int): Boolean = id in taskIds

    override fun addTask(task: Task) {
        val id = getId(counter)
        taskIds.add(id)
        if (task !is MessageReceiveTask) {
            tasks.add(ArrayDeque(listOf(id to task)))
            return
        }
        val queue = tasks.find {
            val top = it.peek()?.second ?: false
            top is MessageReceiveTask && top.from == task.from && top.iNode == task.iNode
        }
        if (queue != null) {
            queue.add(id to task)
        } else {
            tasks.add(ArrayDeque(listOf(id to task)))
        }
    }

    override fun getTaskById(taskId: Int): Task? {
        taskIds.remove(taskId)
        val queue = tasks.find { it.peek()?.first == taskId } ?: return null
        return queue.poll().second.also {
            if (queue.isEmpty()) {
                tasks.remove(queue)
            }
        }
    }

    override fun getAvailableTasks(): Map<Int, Task> {
        tasks.removeAll { it.isEmpty() }
        if (tasks.isEmpty()) {
            val minValue = timers.minOfOrNull { it.value.ticks }
            val nextTimers = timers.filter { it.value.ticks == minValue }
            nextTimers.forEach { (k, v) ->
                tasks.add(ArrayDeque(listOf(k to v)))
                timers.remove(k)
            }
        }
        return tasks.associate { it.peek().first to it.peek().second }
    }

    override fun removeAllForNode(iNode: Int) {
        super.removeAllForNode(iNode)
        tasks.removeAll { it.isEmpty() || it.peek().second.iNode == iNode }
    }
}

