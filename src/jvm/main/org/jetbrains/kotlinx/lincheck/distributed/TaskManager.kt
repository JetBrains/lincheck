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


sealed class Task {
    abstract val id: Int
    abstract val iNode: Int
    abstract val action: suspend () -> Unit
}

data class MessageReceiveTask(
    override val id: Int,
    override val iNode: Int,
    val from: Int,
    override val action: suspend () -> Unit
) : Task()

data class ActionTask(
    override val id: Int,
    override val iNode: Int,
    override val action: suspend () -> Unit
) : Task()

sealed class TimeTask() : Task() {
    abstract val time: Int
}

data class PeriodicTimer(
    override val id: Int,
    override val time: Int,
    override val iNode: Int,
    override val action: suspend () -> Unit
) : TimeTask()

data class Timeout(
    override val id: Int,
    override val time: Int,
    override val iNode: Int,
    override val action: suspend () -> Unit
) : TimeTask()

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
                _tasks.filterIsInstance<ActionTask>() + nextMessagesForFifoOrder()
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

    fun addMessageReceiveTask(from: Int, to: Int, action: suspend () -> Unit): MessageReceiveTask {
        val task = MessageReceiveTask(id = _taskId++, iNode = to, from = from, action = action)
        _tasks.add(task)
        return task
    }

    fun addActionTask(iNode: Int, action: suspend () -> Unit): ActionTask {
        val task = ActionTask(id = _taskId++, iNode = iNode, action = action)
        _tasks.add(task)
        return task
    }

    fun addTimer(iNode: Int, ticks: Int, action: suspend () -> Unit): TimeTask {
        val task = PeriodicTimer(id = _taskId++, time = _time + ticks, iNode = iNode, action = action)
        _timeTasks.add(task)
        return task
    }

    fun addTimeout(iNode: Int, ticks: Int, action: suspend () -> Unit): TimeTask {
        val task = Timeout(id = _taskId++, time = _time + ticks, iNode = iNode, action = action)
        _timeTasks.add(task)
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
        _tasks.removeAll { it.iNode == iNode }
        _timeTasks.removeAll { it.iNode == iNode }
    }
}