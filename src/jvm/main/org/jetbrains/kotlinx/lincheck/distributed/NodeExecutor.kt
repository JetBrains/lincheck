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

import kotlinx.atomicfu.AtomicInt
import org.jetbrains.kotlinx.lincheck.distributed.NodeExecutorStatus.*
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

private enum class NodeExecutorStatus { INIT, RUNNING, STOPPED }

class NodeExecutor(val counter: AtomicInteger, val numberOfNodes: Int) : Executor {
    companion object {
       private const val NO_TASK_LIMIT = 3
    }

    private var executorStatus = INIT
    private val queue = LinkedBlockingQueue<Runnable>()
    private var nullTasksCounter = 0
    private val thread = Thread {
        while (executorStatus != STOPPED) {
            val task = queue.poll()
            task?.process() ?: onNull()
        }
    }

    private fun Runnable.process() {
        if (unknownState) {
            counter.decrementAndGet()
        }
        nullTasksCounter = 0
        run()
    }

    private val unknownState = executorStatus == RUNNING && nullTasksCounter >= NO_TASK_LIMIT


    private fun onNull() {
        nullTasksCounter++
        if (nullTasksCounter == NO_TASK_LIMIT) {
            counter.incrementAndGet()
        }
        if (counter.get() == numberOfNodes) {
            executorStatus = STOPPED
        }
    }

    override fun execute(command: Runnable) {
        queue.put(command)
        if (executorStatus == INIT) {
            executorStatus = RUNNING
            thread.start()
        }
    }

    fun shutdown() {
        thread.join()
    }
}