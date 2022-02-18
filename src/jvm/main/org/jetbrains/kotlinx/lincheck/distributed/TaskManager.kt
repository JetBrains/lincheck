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

import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder.ASYNCHRONOUS
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder.FIFO
import java.util.*

/**
 * Task which belongs to specified [iNode].
 */
interface NodeTask {
    val iNode: Int
}

/**
 * Abstract class for task with unique [id]. Task is an action happening in the system.
 */
sealed class Task {
    abstract val id: Int
}

/**
 * Task which [action] is not suspendable.
 */
sealed class InstantTask : Task() {
    abstract val action: () -> Unit
}

/**
 * Task to deliver message from node [from] to [iNode].
 */
data class MessageReceiveTask(
    override val id: Int,
    override val iNode: Int,
    val from: Int,
    override val action: () -> Unit
) : InstantTask(), NodeTask

/**
 * Other tasks which are not suspended and not MessageReceiveTask.
 */
data class ActionTask(
    override val id: Int,
    override val iNode: Int,
    override val action: () -> Unit
) : InstantTask(), NodeTask

/**
 * Task which should not be taken immediately for execution.
 * [time] is an approximate time when the task should be completed.
 */
sealed class TimeTask : InstantTask() {
    abstract val time: Int
}

/**
 * Task for a tick of periodic timer. See [NodeEnvironment.setTimer]
 */
data class PeriodicTimer(
    override val id: Int,
    override val time: Int,
    val name: String,
    override val iNode: Int,
    override val action: () -> Unit
) : TimeTask(), NodeTask

/**
 * Task for a timeout. See [NodeEnvironment.withTimeout]
 */
data class Timeout(
    override val id: Int,
    override val time: Int,
    override val iNode: Int,
    override val action: () -> Unit
) : TimeTask(), NodeTask

/**
 * Task for recovering node from crash.
 */
data class CrashRecoverTask(
    override val id: Int,
    override val time: Int,
    override val iNode: Int,
    override val action: () -> Unit
) : TimeTask(), NodeTask

/**
 * Task for recovering partition.
 */
data class PartitionRecoverTask(
    override val id: Int,
    override val time: Int,
    override val action: () -> Unit
) : TimeTask()

/**
 * Task for suspendable [action] (operations in scenario).
 */
data class SuspendedTask(
    override val id: Int,
    override val iNode: Int,
    val action: suspend () -> Unit
) : Task(), NodeTask

private interface TaskStorage {
    val available: List<Task>

    fun add(task: Task)

    fun remove(task: Task)

    fun removeAll(iNode: Int)

    fun isEmpty() = available.isEmpty()
}

private fun <E> MutableList<E>.removeAndReturn(predicate: (E) -> Boolean): E? {
    val index = indexOfFirst { predicate(it) }
    if (index != -1) return removeAt(index)
    return null
}

private class FifoStorage(val nodeCount: Int) : TaskStorage {
    override val available = mutableListOf<Task>()
    private val pendingMessages = Array(nodeCount * nodeCount) { LinkedList<MessageReceiveTask>() }

    override fun add(task: Task) {
        if (task !is MessageReceiveTask) {
            available.add(task)
            return
        }
        val id = task.iNode * nodeCount + task.from
        if (pendingMessages[id].isEmpty()) {
            available.add(task)
        }
        pendingMessages[id].add(task)
    }

    override fun remove(task: Task) {
        available.remove(task)
        if (task !is MessageReceiveTask) return
        val id = task.iNode * nodeCount + task.from
        pendingMessages[id].remove()
        val next = pendingMessages[id].peek() ?: return
        available.add(next)
    }

    override fun removeAll(iNode: Int) {
        repeat(nodeCount) {
            pendingMessages[iNode * nodeCount + it].clear()
        }
        available.removeAll { it is NodeTask && it.iNode == iNode }
    }
}

private class AsynchronousStorage : TaskStorage {
    override val available = mutableListOf<Task>()

    override fun add(task: Task) {
        available.add(task)
    }

    override fun remove(task: Task) {
        available.remove(task)
    }

    override fun removeAll(iNode: Int) {
        available.removeAll { it is NodeTask && it.iNode == iNode }
    }
}

/**
 * Stores the tasks which arise during the execution and the logical time.
 * The time is incremented when the task is taken for execution. If there are only time tasks left,
 * the time is set to the minimum time of the time tasks.
 */
internal class TaskManager(messageOrder: MessageOrder, nodeCount: Int) {
    private var _taskId: Int = 0
    private var _time: Int = 0
    private val _tasks: TaskStorage = when (messageOrder) {
        FIFO -> FifoStorage(nodeCount)
        ASYNCHRONOUS -> AsynchronousStorage()
    }
    private val _timeTasks = mutableListOf<TimeTask>()
    private val _interleaving = mutableListOf<Int>()

    val taskLimitExceeded: Boolean
        get() = DistributedOptions.TASK_LIMIT < _taskId

    val interleaving: List<Int>
        get() = _interleaving

    /**
     * Returns the list of tasks which can be executed next. Doesn't include time tasks.
     */
    val tasks: List<Task>
        get() = _tasks.available

    /**
     * Returns the list of time tasks.
     */
    val timeTasks: List<TimeTask>
        get() = _timeTasks

    /**
     * Updates the time if necessary and returns the current time.
     */
    val time: Int
        get() {
            if (_tasks.isEmpty() && _timeTasks.isNotEmpty()) {
                _time = _timeTasks.minOf { it.time }
            }
            return _time
        }

    /**
     * Removes all tasks for [iNode] after crash.
     */
    fun removeAllForNode(iNode: Int) {
        _tasks.removeAll(iNode)
        _timeTasks.removeAll { it is NodeTask && it.iNode == iNode }
    }

    /**
     * Remove [PeriodicTimer] with name [name] for [iNode].
     */
    fun removeTimer(name: String, iNode: Int) {
        _timeTasks.removeAll { it is PeriodicTimer && it.name == name && it.iNode == iNode }
    }

    /**
     * Removes [task] and increments the time.
     */
    fun removeTask(task: Task) {
        _time++
        _interleaving.add(task.id)
        when (task) {
            is TimeTask -> _timeTasks.remove(task)
            else -> {
                _tasks.remove(task)
            }
        }
    }

    /**
     * Creates [MessageReceiveTask]
     */
    fun addMessageReceiveTask(
        from: Int,
        to: Int,
        action: () -> Unit
    ): MessageReceiveTask {
        val task = MessageReceiveTask(
            id = _taskId++,
            iNode = to,
            from = from,
            action = action
        )
        _tasks.add(task)
        return task
    }

    /**
     * Creates [ActionTask].
     */
    fun addActionTask(iNode: Int, action: () -> Unit): ActionTask {
        val task =
            ActionTask(id = _taskId++, iNode = iNode, action = action)
        _tasks.add(task)
        return task
    }

    /**
     * Creates [PeriodicTimer].
     * [ticks] is the approximate time before the task should be completed.
     */
    fun addTimer(iNode: Int, ticks: Int, name: String, action: () -> Unit): PeriodicTimer {
        val task = PeriodicTimer(
            id = _taskId++,
            time = _time + ticks,
            name = name,
            iNode = iNode,
            action = action
        )
        _timeTasks.add(task)
        return task
    }

    /**
     * Creates [Timeout].
     * [ticks] is the approximate time before the task should be completed.
     */
    fun addTimeout(iNode: Int, ticks: Int, action: () -> Unit): Timeout {
        val task = Timeout(
            id = _taskId++,
            time = _time + ticks,
            iNode = iNode,
            action = action
        )
        _timeTasks.add(task)
        return task
    }

    /**
     * Creates [CrashRecoverTask].
     */
    fun addCrashRecoverTask(iNode: Int, ticks: Int, action: () -> Unit): CrashRecoverTask {
        val task = CrashRecoverTask(
            id = _taskId++,
            time = _time + ticks,
            iNode = iNode,
            action = action
        )
        _timeTasks.add(task)
        return task
    }

    /**
     * Creates [PartitionRecoverTask].
     */
    fun addPartitionRecoverTask(ticks: Int, action: () -> Unit): PartitionRecoverTask {
        val task = PartitionRecoverTask(
            id = _taskId++,
            time = _time + ticks,
            action = action
        )
        _timeTasks.add(task)
        return task
    }

    /**
     * Creates [SuspendedTask].
     */
    fun addSuspendedTask(iNode: Int, action: suspend () -> Unit): SuspendedTask {
        val task =
            SuspendedTask(id = _taskId++, iNode = iNode, action = action)
        _tasks.add(task)
        return task
    }
}
