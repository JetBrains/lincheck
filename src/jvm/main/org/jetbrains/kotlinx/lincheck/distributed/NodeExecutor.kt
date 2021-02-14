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

import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.distributed.NodeExecutorStatus.*
import org.jetbrains.kotlinx.lincheck.distributed.stress.LogLevel
import org.jetbrains.kotlinx.lincheck.distributed.stress.logMessage
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private enum class NodeExecutorStatus { INIT, RUNNING, STOPPED }

class NodeExecutor(
    val counter: AtomicInteger,
    val numberOfNodes: Int,
    val id : Int,
    val semaphore: Semaphore
) : Executor {
    companion object {
        private const val NO_TASK_LIMIT = 100
    }

    private var executorStatus = INIT
    private val queue = LinkedBlockingQueue<Runnable>()
    private var nullTasksCounter = 0
    private val worker = thread(start = false, name = "NodeExecutorThread-$id") {
        while (executorStatus != STOPPED) {
            val task = queue.poll()
            task?.process() ?: onNull()
        }
    }

    private fun Runnable.process() {
        if (unknownState) {
            logMessage(LogLevel.ALL_EVENTS) {
                println("$id Decrement counter")
            }
            counter.decrementAndGet()
        }
        nullTasksCounter = 0
        run()
    }

    private val unknownState = executorStatus == RUNNING && nullTasksCounter >= NO_TASK_LIMIT


    private fun onNull() {
        nullTasksCounter++
        if (nullTasksCounter == NO_TASK_LIMIT) {
            logMessage(LogLevel.ALL_EVENTS) {
                println("$id Increment counter")
            }
            counter.incrementAndGet()
        }
        if (counter.get() == numberOfNodes && executorStatus != STOPPED) {
            executorStatus = STOPPED
            logMessage(LogLevel.ALL_EVENTS) {
                println("NodeExecutors stopped ${semaphore.availablePermits}")
            }
            logMessage(LogLevel.ALL_EVENTS) {
                println("Semaphore release")
            }
            semaphore.release()
        }
    }

    override fun execute(command: Runnable) {
        queue.put(command)
        if (executorStatus == INIT) {
            executorStatus = RUNNING
            worker.start()
        }
    }

    fun join() = worker.join()
}