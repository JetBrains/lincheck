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
import kotlinx.coroutines.Runnable
import org.jetbrains.kotlinx.lincheck.distributed.NodeDispatcher.Companion.NodeDispatcherStatus.*
import org.jetbrains.kotlinx.lincheck.distributed.stress.LogLevel
import org.jetbrains.kotlinx.lincheck.distributed.stress.logMessage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Counts the total number of pending tasks for the [NodeDispatcher] to execute.
 * Signals to the main thread when the execution is other.
 */
class DispatcherTaskCounter(
    private val initialCounter: Int,
    private val numberOfNodes: Int
) {
    private val counter = atomic(initialCounter)
    private val nodeOperationCounter = ThreadLocal.withInitial { 0 }
    val signal = Signal()

    /**
     * Signals to the waiting thread if there are no tasks left.
     */
    fun checkIsFinished(f: () -> Unit) {
        if (counter.value == 0) {
            signal.signal()
            f()
        }
    }

    /**
     * Returns the current number of tasks.
     */
    fun get() = counter.value

    /**
     * Increments the number of tasks.
     */
    fun increment() = counter.incrementAndGet()

    /**
     * Decrements the number of tasks.
     */
    fun decrement() = counter.decrementAndGet()

    /**
     * Adds [delta] to the current number of tasks.
     */
    fun add(delta: Int) = counter.addAndGet(delta)

    /**
     * Runs the given suspended function [f] and guarantees that
     * the execution will not finish before the function is executed or the
     * node crash occurs.
     *
     * To achieve it, the task counter is incremented before calling [f] and decremented after [f] is executed.
     * However, if [f] suspends and when crash occurs, the finally-block will never be executed and the total sum will be greater than zero.
     * To handle it, the extra thread-local counter [nodeOperationCounter] is used (see [clear]).
     */
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

    /**
     * Clears all suspended functions which were called inside [runSafely].
     * Called when the node crash occurs. The total counter is decreased by the value of the local counter [nodeOperationCounter],
     * and the local counter is set to zero.
     */
    fun clear() {
        val delta = -nodeOperationCounter.get()
        add(delta)
        nodeOperationCounter.set(0)
    }

    /**
     * Awaits until no tasks left.
     */
    suspend fun await() = signal.await()

    /**
     * Resets the counter for a new invocation.
     */
    fun reset() {
        counter.lazySet(initialCounter)
        signal.reset()
    }
}

/**
 * Indicates that the counter [DispatcherTaskCounter] for this coroutine was already incremented
 * and should not be incremented inside [NodeDispatcher]. Stops being taken into account after the first time,
 * as the counter was incremented only for the initial task.
 */
class AlreadyIncrementedCounter : AbstractCoroutineContextElement(Key) {
    @Volatile
    var isUsed = false

    companion object Key : CoroutineContext.Key<AlreadyIncrementedCounter>
}

class InvocationContext(val invocation: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<InvocationContext>
}

/**
 * The dispatcher for executing task related to a single [Node] inside [org.jetbrains.kotlinx.lincheck.distributed.stress.DistributedRunner].
 */
class NodeDispatcher(val id: Int, val taskCounter: DispatcherTaskCounter, val runnerHash: Int) : CoroutineDispatcher() {
    companion object {
        enum class NodeDispatcherStatus { RUNNING, STOPPED, CRASHED }

        @Volatile
        var invocation = 0
    }

    private val status = atomic(RUNNING)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> NodeTestThread(id, runnerHash, r) }

    /**
     * Thread to run tasks related to a specified node.
     */
    inner class NodeTestThread(val iThread: Int, val runnerHash: Int, r: Runnable) :
        Thread(r, "NodeExecutor@$runnerHash-$iThread-${this@NodeDispatcher.hashCode()}")

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        logMessage(LogLevel.ALL_EVENTS) {
            "[$id]: Before submit task ${block.hashCode()} context[InvocationContext.Key]?.invocation != invocation"
        }
        if (context[InvocationContext.Key]?.invocation != invocation) {
            return
        }
        val shouldInc = context[AlreadyIncrementedCounter.Key]?.isUsed != false
        if (status.value == CRASHED) {
            // If the node has crashed the counter should be decreased for the initial tasks.
            if (!shouldInc) {
                taskCounter.decrement()
                context[AlreadyIncrementedCounter.Key]!!.isUsed = true
                taskCounter.checkIsFinished {
                    status.lazySet(STOPPED)
                }
            }
            logMessage(LogLevel.ALL_EVENTS) {
                "[$id]: Shutdown, ${hashCode()} try to submit task ${block.hashCode()}, $shouldInc, ${taskCounter.get()}"
            }
            return
        }
        if (status.value == STOPPED) {
            logMessage(LogLevel.ALL_EVENTS) {
                "[$id]: Try ${hashCode()} to submit task ${block.hashCode()}"
            }
            return
        }
        val r = if (shouldInc) {
            taskCounter.increment()
        } else {
            context[AlreadyIncrementedCounter.Key]?.isUsed = true
            taskCounter.get()
        }
        logMessage(LogLevel.ALL_EVENTS) {
            "[$id]: Submit task $shouldInc ${block.hashCode()} counter is $r"
        }
        try {
            executor.submit {
                logMessage(LogLevel.ALL_EVENTS) {
                    "[$id]: Run task ${block.hashCode()} counter is ${taskCounter.get()}"
                }
                if (status.value == RUNNING) {
                    block.run()
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
        } catch (_: RejectedExecutionException) {
            return
        }
    }

    /**
     * Sets status to CRASHED and clears the counter for this thread.
     */
    fun crash() {
        status.lazySet(CRASHED)
        taskCounter.clear()
        logMessage(LogLevel.ALL_EVENTS) {
            "[$id]: Crashed executor ${hashCode()}"
        }
    }

    /**
     * Shutdowns the executor.
     */
    fun shutdown() {
        status.lazySet(STOPPED)
        logMessage(LogLevel.ALL_EVENTS) {
            "[$id]: Shutdown executor ${hashCode()}"
        }
        executor.shutdown()
    }

    /**
     * Sets the node status to STOPPED.
     */
    fun stop() = status.lazySet(STOPPED)
}