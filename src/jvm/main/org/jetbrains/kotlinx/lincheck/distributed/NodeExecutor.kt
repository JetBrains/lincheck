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

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.distributed.NodeExecutorStatus.*
import org.jetbrains.kotlinx.lincheck.distributed.stress.LogLevel
import org.jetbrains.kotlinx.lincheck.distributed.stress.logMessage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private enum class NodeExecutorStatus { RUNNING, STOPPED }

class NodeExecutorContext(private val initialTaskCounter: Int, private val permits: Int) {
    private val taskCounter = atomic(initialTaskCounter)
    val semaphore = Semaphore(permits, permits)

    fun checkIsFinished(f: () -> Unit) {
        if (taskCounter.value == 0) {
            semaphore.release()
            f()
        }
    }

    fun get() = taskCounter.value

    fun increment() = taskCounter.incrementAndGet()

    fun decrement() = taskCounter.decrementAndGet()

    fun add(delta : Int) = taskCounter.addAndGet(delta)
}

class NodeExecutor(
    val id: Int,
    val context: NodeExecutorContext,
    private val hash: Int
) : Executor {
    private lateinit var semaphore: Semaphore
    private val executor = Executors.newSingleThreadExecutor { r -> NodeTestThread(id, hash, r) }

    inner class NodeTestThread(val iThread: Int, val runnerHash: Int, r: Runnable) :
        Thread(r, "NodeExecutor@$runnerHash-$iThread-${this@NodeExecutor.hashCode()}")

    private val executorStatus = atomic(RUNNING)

    override fun execute(command: Runnable) {
        if (executorStatus.value == STOPPED) {
            logMessage(LogLevel.ALL_EVENTS) {
                "[$id]: Try to submit task ${command.hashCode()}"
            }
            throw RejectedExecutionException("The executor is finished")
        }
        val curThread = Thread.currentThread()
        val r = if (curThread is NodeTestThread) {
            context.increment()
        } else {
            context.get()
        }
        logMessage(LogLevel.ALL_EVENTS) {
            "[$id]: Submit task ${command.hashCode()} counter is $r"
        }
        executor.execute {
            logMessage(LogLevel.ALL_EVENTS) {
                "[$id]: Run task ${command.hashCode()} counter is ${context.get()}"
            }
            command.run()
            if (executorStatus.value != STOPPED) {
                val t = context.decrement()
                logMessage(LogLevel.ALL_EVENTS) {
                    "[$id]: Finish task ${command.hashCode()} counter is ${t}"
                }
                context.checkIsFinished {
                    logMessage(LogLevel.ALL_EVENTS) {
                        "[$id]: Release semaphore"
                    }
                    executorStatus.lazySet(STOPPED)
                }
            }
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}