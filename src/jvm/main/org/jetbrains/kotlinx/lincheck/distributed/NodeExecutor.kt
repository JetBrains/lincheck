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

internal enum class NodeExecutorStatus { RUNNING, STOPPED, CRASHED }

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

    fun add(delta: Int) = taskCounter.addAndGet(delta)
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
        val curThread = Thread.currentThread()
        if (executorStatus.value == CRASHED) {
            // If we didn't launch the task and the node has already failed.
            if (curThread !is NodeTestThread) {
                logMessage(LogLevel.ALL_EVENTS) {
                    "[$id]: Decrement context"
                }
                context.decrement()
            }
            logMessage(LogLevel.ALL_EVENTS) {
                "[$id]: Shutdown, ${hashCode()} try to submit task ${command.hashCode()}, dangerous"
            }
            return
        }
        if (executorStatus.value == STOPPED) {
            logMessage(LogLevel.ALL_EVENTS) {
                "[$id]: Try ${hashCode()} to submit task ${command.hashCode()}"
            }
            throw RejectedExecutionException("The executor is finished")
        }

        // Check if it is initial task or task made by another task.
        val r = if (curThread is NodeTestThread) {
            context.increment()
        } else {
            context.get()
        }
        logMessage(LogLevel.ALL_EVENTS) {
            "[$id]: Submit task ${command.hashCode()} $command counter is $r"
        }
        executor.execute {
            logMessage(LogLevel.ALL_EVENTS) {
                "[$id]: Run task ${command.hashCode()} counter is ${context.get()}"
            }
            // Execute task only if node is running, otherwise ignore
            if (executorStatus.value == RUNNING) {
                command.run()
            }
            if (executorStatus.value != STOPPED) {
                // Decrement counter for all submitted tasks, even if node is crashed.
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

    internal fun shutdown(status: NodeExecutorStatus = STOPPED) {
        executorStatus.lazySet(status)
        logMessage(LogLevel.ALL_EVENTS) {
            "[$id]: Shutdown executor ${hashCode()}"
        }
        executor.shutdown()
    }
}