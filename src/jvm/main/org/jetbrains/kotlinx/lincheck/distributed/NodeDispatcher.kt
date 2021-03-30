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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Runnable
import org.jetbrains.kotlinx.lincheck.distributed.NodeDispatcher.Companion.NodeDispatcherStatus.*
import org.jetbrains.kotlinx.lincheck.distributed.stress.LogLevel
import org.jetbrains.kotlinx.lincheck.distributed.stress.logMessage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val handler = CoroutineExceptionHandler { _, _ -> }

class DispatcherTaskCounter(
    private val initialCounter: Int,
    private val numberOfNodes: Int
) {
    private val taskCounter = atomic(initialCounter)
    private val nodeOperationCounter = ThreadLocal.withInitial { 0 }
    val signal = Signal()

    fun checkIsFinished(f: () -> Unit) {
        if (taskCounter.value == 0) {
            signal.signal()
            f()
        }
    }

    fun get() = taskCounter.value

    fun increment() = taskCounter.incrementAndGet()

    fun decrement() = taskCounter.decrementAndGet()

    fun add(delta: Int) = taskCounter.addAndGet(delta)

    suspend fun <T> runSafely(f: suspend () -> T): T {
        increment()
        val incVal = nodeOperationCounter.get() + 1
        nodeOperationCounter.set(incVal)
        try {
            return f()
        } finally {
            if (nodeOperationCounter.get() != 0) {
                decrement()
                val decValue = nodeOperationCounter.get() - 1
                nodeOperationCounter.set(decValue)
            }
        }
    }

    fun clear() {
        val delta = -nodeOperationCounter.get()
        add(delta)
        nodeOperationCounter.set(0)
    }
}

class AlreadyIncrementedCounter : AbstractCoroutineContextElement(Key) {

    @Volatile
    var isUsed = false

    companion object Key : CoroutineContext.Key<AlreadyIncrementedCounter>
}

class NodeDispatcher(val id: Int, val taskCounter: DispatcherTaskCounter, val runnerHash: Int) : CoroutineDispatcher() {
    companion object {
        internal enum class NodeDispatcherStatus { RUNNING, STOPPED, CRASHED }
    }

    private val status = atomic(RUNNING)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> NodeTestThread(id, runnerHash, r) }

    inner class NodeTestThread(val iThread: Int, val runnerHash: Int, r: Runnable) :
        Thread(r, "NodeExecutor@$runnerHash-$iThread-${this@NodeDispatcher.hashCode()}")

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (status.value == CRASHED) {
            if (context[AlreadyIncrementedCounter.Key]?.isUsed == false) {
                taskCounter.decrement()
                context[AlreadyIncrementedCounter.Key]!!.isUsed = true
            }
            logMessage(LogLevel.ALL_EVENTS) {
                "[$id]: Shutdown, ${hashCode()} try to submit task ${block.hashCode()}, dangerous"
            }
            return
        }
        if (status.value == STOPPED) {
            logMessage(LogLevel.ALL_EVENTS) {
                "[$id]: Try ${hashCode()} to submit task ${block.hashCode()}"
            }
            return
            //throw RejectedExecutionException()
        }
        val shouldInc = context[AlreadyIncrementedCounter.Key]?.isUsed != false
        val r = if (context[AlreadyIncrementedCounter.Key]?.isUsed != false) {
            taskCounter.increment()
        } else {
            context[AlreadyIncrementedCounter.Key]?.isUsed = true
            taskCounter.get()
        }
        logMessage(LogLevel.ALL_EVENTS) {
            "[$id]: Submit task $shouldInc ${block.hashCode()} counter is $r"
        }
        executor.submit {
            logMessage(LogLevel.ALL_EVENTS) {
                "[$id]: Run task ${block.hashCode()} counter is ${taskCounter.get()}"
            }
            if (status.value == RUNNING) {
                try {
                    block.run()
                } catch (e: Throwable) {
                    println("Exception here $e")
                    logMessage(LogLevel.ALL_EVENTS) {
                        "[$id]: Exception $e while running block"
                    }
                }
            }
            if (status.value != STOPPED) {
                val t = taskCounter.decrement()
                logMessage(LogLevel.ALL_EVENTS) {
                    "[$id]: Finish task ${block.hashCode()} counter is ${t}"
                }
                taskCounter.checkIsFinished {
                    logMessage(LogLevel.ALL_EVENTS) {
                        "[$id]: Release semaphore"
                    }
                    status.lazySet(STOPPED)
                }
            }
        }
    }

    internal fun crash() {
        status.lazySet(CRASHED)
        taskCounter.clear()
        logMessage(LogLevel.ALL_EVENTS) {
            "[$id]: Crashed executor ${hashCode()}"
        }
    }

    internal fun shutdown() {
        this.status.lazySet(STOPPED)
        logMessage(LogLevel.ALL_EVENTS) {
            "[$id]: Shutdown executor ${hashCode()}"
        }
        executor.shutdown()
    }
}