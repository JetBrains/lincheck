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

import org.jetbrains.kotlinx.lincheck.distributed.MessageSentEvent
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.runner.TestNodeExecution
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.random.Random


sealed class Task : Comparable<Task> {
    abstract val priority: Int

    override fun compareTo(other: Task): Int {
        return priority - other.priority
    }
}

data class MessageTask<Message>(
    val sender: Int,
    val event: MessageSentEvent<Message>,
    override val priority: Int
) : Task()

data class OperationTask(
    val testNodeExecution: TestNodeExecution,
    val testInstance: Class<out Node<*>>,
    override val priority: Int
) : Task()

data class TimeoutTask(
    val continuation: Continuation<Unit>,
    override val priority: Int
) : Task()

data class TimerTask(
    val name: String,
    val f: suspend () -> Unit,
    val ticks: Int,
    override val priority: Int
) : Task()

data class SleepTask(
    val continuation: Continuation<Unit>,
    override val priority: Int
) : Task()

class TaskManager(context: ModelCheckingContext<*, *>) {
    val random = Random(0)

    val tasks = Array<PriorityQueue<Task>>(context.addressResolver.totalNumberOfNodes) {
        PriorityQueue()
    }

    fun nextTask(): Task? {
        val task = tasks.filter { it.isNotEmpty() }.randomOrNull(random)?.poll()
        return task
    }

    fun addTask(iNode: Int, task: Task) {
        tasks[iNode].add(task)
    }

    fun lastPriority(iNode: Int) = tasks[iNode].last().priority
}