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

interface NodeTask {
    val iNode: Int
}

sealed class Task {
    abstract val id: Int
}

sealed class InstantTask : Task() {
    abstract val action: () -> Unit
}

data class MessageReceiveTask(
    override val id: Int,
    override val iNode: Int,
    val from: Int,
    override val action: () -> Unit
) : InstantTask(), NodeTask

data class ActionTask(
    override val id: Int,
    override val iNode: Int,
    override val action: () -> Unit
) : InstantTask(), NodeTask

sealed class TimeTask : InstantTask() {
    abstract val time: Int
}

data class PeriodicTimer(
    override val id: Int,
    override val time: Int,
    override val iNode: Int,
    override val action: () -> Unit
) : TimeTask(), NodeTask

data class Timeout(
    override val id: Int,
    override val time: Int,
    override val iNode: Int,
    override val action: () -> Unit
) : TimeTask(), NodeTask

data class CrashRecoverTask(
    override val id: Int,
    override val time: Int,
    override val iNode: Int,
    override val action: () -> Unit
) : TimeTask(), NodeTask

data class PartitionRecoverTask(
    override val id: Int,
    override val time: Int,
    override val action: () -> Unit
) : TimeTask()

data class SuspendedTask(
    override val id: Int,
    override val iNode: Int,
    val action: suspend () -> Unit
) : Task(), NodeTask

internal class TaskManager(private val messageOrder: MessageOrder) {
    private var _taskId: Int = 0
    private var _time: Int = 0
    private val _tasks = mutableListOf<Task>()
    private val _timeTasks = mutableListOf<TimeTask>()

    private fun nextMessagesForFifoOrder(): List<Task> {
        val senderReceiverPairs = mutableSetOf<Pair<Int, Int>>()
        return _tasks.filterIsInstance<MessageReceiveTask>().filter { senderReceiverPairs.add(it.iNode to it.from) }
    }

    val tasks: List<Task>
        get() = when (messageOrder) {
            MessageOrder.ASYNCHRONOUS -> _tasks
            MessageOrder.FIFO -> {
                _tasks.filter { it !is MessageReceiveTask } + nextMessagesForFifoOrder()
            }
        }

    val timeTasks: List<TimeTask>
        get() = _timeTasks

    val time: Int
        get() {
            if (_tasks.isEmpty() && _timeTasks.isNotEmpty()) {
                _time = _timeTasks.minOf { it.time }
            }
            return _time
        }

    val allTasks: List<Task>
        get() = _tasks + _timeTasks

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

    fun addActionTask(iNode: Int, action: () -> Unit): ActionTask {
        val task =
            ActionTask(id = _taskId++, iNode = iNode, action = action)
        _tasks.add(task)
        return task
    }

    fun addTimer(iNode: Int, ticks: Int, action: () -> Unit): PeriodicTimer {
        val task = PeriodicTimer(
            id = _taskId++,
            time = _time + ticks,
            iNode = iNode,
            action = action
        )
        _timeTasks.add(task)
        return task
    }

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

    fun addPartitionRecoverTask(ticks: Int, action: () -> Unit): PartitionRecoverTask {
        val task = PartitionRecoverTask(
            id = _taskId++,
            time = _time + ticks,
            action = action
        )
        _timeTasks.add(task)
        return task
    }

    fun addSuspendedTask(iNode: Int, action: suspend () -> Unit): SuspendedTask {
        val task =
            SuspendedTask(id = _taskId++, iNode = iNode, action = action)
        _tasks.add(task)
        return task
    }

    fun removeTask(task: Task) {
        _time++
        if (task is TimeTask) {
            _timeTasks.remove(task)
        } else {
            _tasks.remove(task)
        }
    }

    fun removeAllForNode(iNode: Int) {
        _tasks.removeAll { it is NodeTask && it.iNode == iNode }
        _timeTasks.removeAll { it is NodeTask && it.iNode == iNode }
    }
}