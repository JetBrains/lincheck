/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.runner

import kotlinx.atomicfu.*
import kotlinx.coroutines.CancellableContinuation
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import java.io.*
import java.lang.*
import java.util.concurrent.*
import java.util.concurrent.locks.*
import kotlin.math.*

/**
 * This executor maintains the specified number of threads and is used by
 * [ParallelThreadsRunner] to execute [ExecutionScenario]-s. The main feature
 * is that this executor keeps the re-using threads "hot" (active) as long as
 * possible, so that they should not be parked and unparked between invocations.
 */
internal class FixedActiveThreadsExecutor(private val nThreads: Int, runnerHash: Int) : Closeable {
    // Threads used in this runner.
    val threads: List<TestThread>
    /**
     * null, waiting TestThread, Runnable task, or SHUTDOWN
     */
    private val tasks = atomicArrayOfNulls<Any>(nThreads)
    /**
     * null, waiting in [submitAndAwait] thread, DONE, or exception
     */
    private val results = atomicArrayOfNulls<Any>(nThreads)
    /**
     * Specifies the number of loop cycles for threads
     * active waiting, after that they should be parked
     */
    private var spinCount = 40000
    /**
     * An adaptive active waiting strategy is used for the case when
     * the number of threads is greater than the number of cores.
     * This flag is set to `true` when any of the threads was parked
     * during the previous [submitAndAwait] call.
     */
    @Volatile
    private var wasParked: Boolean = false
    /**
     * This balance is either increased or decreased by 1 at the
     * end of each invocation when [wasParked] is `true` or `false`
     * correspondingly. When the balance achieves [WAS_PARK_BALANCE_THRESHOLD],
     * [spinCount] is doubled, and when the balance achieves
     * -[WAS_PARK_BALANCE_THRESHOLD], [spinCount] is halved.
     */
    private var wasParkedBalance: Int = 0

    /**
     * This flag is set to `true` when [await] detects a hang.
     * In this case, when this executor is closed, [Thread.stop]
     * is called on all the internal threads.
     */
    private var hangDetected = false

    init {
        threads = (0 until nThreads).map { iThread ->
            TestThread(iThread, runnerHash, testThreadRunnable(iThread)).also { it.start() }
        }
    }

    /**
     * Submits the specified set of [tasks] to this executor
     * and waits until all of them are completed.
     * The number of tasks should be equal to [nThreads].
     *
     * @throws TimeoutException if more than [timeoutMs] is passed.
     * @throws ExecutionException if an unexpected exception is thrown during the execution.
     */
    fun submitAndAwait(tasks: Array<out Runnable>, timeoutMs: Long) {
        require(tasks.size == nThreads)
        submitTasks(tasks)
        await(timeoutMs)
        updateAdaptiveSpinCount()
    }

    private fun submitTasks(tasks: Array<out Any>) {
        for (i in 0 until nThreads) {
            results[i].value = null
            submitTask(i, tasks[i])
        }
    }

    private fun updateAdaptiveSpinCount() {
        if (wasParked) {
            wasParked = false
            wasParkedBalance++
            if (wasParkedBalance >= WAS_PARK_BALANCE_THRESHOLD) {
                spinCount /= 2
                wasParkedBalance = 0
            }
        } else {
            wasParkedBalance--
            if (wasParkedBalance <= -WAS_PARK_BALANCE_THRESHOLD) {
                spinCount = min(spinCount * 2, MAX_SPIN_COUNT)
                wasParkedBalance = 0
            }
        }
    }

    private fun submitTask(iThread: Int, task: Any) {
        if (tasks[iThread].compareAndSet(null, task)) return
        // CAS failed => a test thread is parked.
        // Submit the task and unpark the waiting thread.
        val thread = tasks[iThread].value as TestThread
        tasks[iThread].value = task
        LockSupport.unpark(thread)
    }

    private fun await(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        for (iThread in 0 until nThreads)
            awaitTask(iThread, deadline)
    }

    private fun awaitTask(iThread: Int, deadline: Long) {
        val result = getResult(iThread, deadline)
        // Check whether there was an exception during the execution.
        if (result != DONE) throw ExecutionException(result as Throwable)
    }

    private fun getResult(iThread: Int, deadline: Long): Any {
        // Active wait for a result during the limited number of loop cycles.
        spinWait { results[iThread].value }?.let {
            return it
        }
        // Park with timeout until the result is set or the timeout is passed.
        val currentThread = Thread.currentThread()
        if (results[iThread].compareAndSet(null, Thread.currentThread())) {
            while (results[iThread].value === currentThread) {
                val timeLeft = deadline - System.currentTimeMillis()
                if (timeLeft <= 0) {
                    hangDetected = true
                    throw TimeoutException()
                }
                LockSupport.parkNanos(timeLeft * 1_000_000)
            }
        }
        return results[iThread].value!!
    }

    private fun testThreadRunnable(iThread: Int) = Runnable {
        loop@while (true) {
            val task = getTask(iThread)
            if (task == SHUTDOWN) return@Runnable
            tasks[iThread].value = null // reset task
            val runnable = task as Runnable
            try {
                runnable.run()
            } catch(e: Throwable) {
                setResult(iThread, wrapInvalidAccessFromUnnamedModuleExceptionWithDescription(e))
                continue@loop
            }
            setResult(iThread, DONE)
        }
    }

    private fun getTask(iThread: Int): Any {
        // Active wait for a task for the limited number of loop cycles.
        spinWait { tasks[iThread].value }?.let {
            return it
        }
        // Park until a task is stored into `tasks[iThread]`.
        val currentThread = Thread.currentThread()
        if (tasks[iThread].compareAndSet(null, Thread.currentThread())) {
            while (tasks[iThread].value === currentThread) {
                LockSupport.park()
            }
        }
        return tasks[iThread].value!!
    }

    private fun setResult(iThread: Int, any: Any) {
        if (results[iThread].compareAndSet(null, any)) return
        // CAS failed => a test thread is parked.
        // Set the result and unpark the waiting thread.
        val thread = results[iThread].value as Thread
        results[iThread].value = any
        LockSupport.unpark(thread)
    }

    private inline fun spinWait(getter: () -> Any?): Any? {
        repeat(spinCount) {
            getter()?.let {
                return it
            }
        }
        wasParked = true
        return null
    }

    override fun close() {
        // submit the shutdown task.
        submitTasks(Array(nThreads) { SHUTDOWN })
        if (hangDetected) {
            for (t in threads) t.stop()
        }
    }

    class TestThread(val iThread: Int, val runnerHash: Int, r: Runnable) : Thread(r, "FixedActiveThreadsExecutor@$runnerHash-$iThread") {
        var cont: CancellableContinuation<*>? = null
    }

    companion object {
        private val SHUTDOWN = Any()
        private val DONE = Any()
        private const val MAX_SPIN_COUNT = 1_000_000
        private const val WAS_PARK_BALANCE_THRESHOLD = 20
    }
}
