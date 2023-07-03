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

/**
 * This executor maintains the specified number of threads and is used by
 * [ParallelThreadsRunner] to execute [ExecutionScenario]-s. The main feature
 * is that this executor keeps the re-using threads "hot" (active) as long as possible,
 * so that they should not be parked and unparked between invocations.
 */
internal class FixedActiveThreadsExecutor(private val nThreads: Int, runnerHash: Int) : Closeable {
    /**
     * null, waiting TestThread, Runnable task, or SHUTDOWN
     */
    private val tasks = atomicArrayOfNulls<Any>(nThreads)

    /**
     * null, waiting in [submitAndAwait] thread, DONE, or exception
     */
    private val results = atomicArrayOfNulls<Any>(nThreads)

    /**
     * This flag is set to `true` when [await] detects a hang.
     * In this case, when this executor is closed, [Thread.stop]
     * is called on all the internal threads.
     */
    private var hangDetected = false

    /**
     * Threads used in this runner.
     */
    val threads = Array(nThreads) { iThread ->
        TestThread(iThread, runnerHash, testThreadRunnable(iThread)).also { it.start() }
    }

    val numberOfThreadsExceedAvailableProcessors = Runtime.getRuntime().availableProcessors() < threads.size

    /**
     * Submits the specified set of [tasks] to this executor
     * and waits until all of them are completed.
     *
     * @param tasks array of tasks to perform. Tasks should be given as instances of [TestThreadExecution] class.
     *   Each [TestThreadExecution] object should specify [TestThreadExecution.iThread] field ---
     *   it determines the index of the thread on which the task will be executed.
     *   These indices should be unique and each index should be within the range of threads allocated to this executor.
     * @param timeoutNano the timeout in nanoseconds to perform submitted tasks.
     * @return The time in milliseconds spent on waiting for the tasks to complete.
     * @throws TimeoutException if more than [timeoutNano] is passed.
     * @throws ExecutionException if an unexpected exception is thrown during the execution.
     */
    fun submitAndAwait(tasks: Array<out TestThreadExecution>, timeoutNano: Long): Long {
        require(tasks.all { it.iThread in 0 until nThreads}) {
            "Submitted tasks contain thread index outside of current executor bounds."
        }
        require(tasks.distinctBy { it.iThread }.size == tasks.size) {
            "Submitted tasks have duplicate thread indices."
        }
        submitTasks(tasks)
        return await(tasks, timeoutNano)
    }

    private fun submitTasks(tasks: Array<out TestThreadExecution>) {
        for (task in tasks) {
            val i = task.iThread
            submitTask(i, task)
        }
    }

    private fun shutdown() {
        // submit the shutdown tasks
        for (i in 0 until nThreads)
            submitTask(i, SHUTDOWN)
    }

    private fun submitTask(iThread: Int, task: Any) {
        results[iThread].value = null
        val old = tasks[iThread].getAndSet(task)
        if (old is TestThread) {
            LockSupport.unpark(old)
        }
    }

    private fun await(tasks: Array<out TestThreadExecution>, timeoutNano: Long): Long {
        val startTime = System.nanoTime()
        val deadline = startTime + timeoutNano
        var exception: Throwable? = null
        for (task in tasks) {
            val e = awaitTask(task.iThread, deadline)
            if (e != null) {
                if (exception == null) {
                    exception = e
                } else {
                    exception.addSuppressed(e)
                }
            }
        }
        exception?.let { throw ExecutionException(it) }
        return System.nanoTime() - startTime
    }

    private fun awaitTask(iThread: Int, deadline: Long): Throwable? {
        val result = getResult(iThread, deadline)
        // Check whether there was an exception during the execution.
        return result as? Throwable
    }

    private fun getResult(iThread: Int, deadline: Long): Any {
        // Active wait for a result during the limited number of loop cycles.
        spinWait { results[iThread].value }?.let {
            return it
        }
        // Park with timeout until the result is set or the timeout is passed.
        val currentThread = Thread.currentThread()
        if (results[iThread].compareAndSet(null, currentThread)) {
            while (results[iThread].value === currentThread) {
                val timeLeft = deadline - System.nanoTime()
                if (timeLeft <= 0) {
                    hangDetected = true
                    throw TimeoutException()
                }
                LockSupport.parkNanos(timeLeft)
            }
        }
        return results[iThread].value!!
    }

    private fun testThreadRunnable(iThread: Int) = Runnable {
        loop@ while (true) {
            val task = getTask(iThread)
            if (task === SHUTDOWN) return@Runnable
            tasks[iThread].value = null // reset task
            val threadExecution = task as TestThreadExecution
            check(threadExecution.iThread == iThread)
            try {
                threadExecution.run()
            } catch(e: Throwable) {
                val wrapped = wrapInvalidAccessFromUnnamedModuleExceptionWithDescription(e)
                setResult(iThread, wrapped)
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
        if (tasks[iThread].compareAndSet(null, currentThread)) {
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
        // Park immediately when the number of threads exceed the number of cores to avoid starvation.
        val spinningLoopIterations = if (numberOfThreadsExceedAvailableProcessors) {
            1
        } else {
            SPINNING_LOOP_ITERATIONS_BEFORE_PARK
        }
        repeat(spinningLoopIterations) {
            getter()?.let {
                return it
            }
        }
        return null
    }

    override fun close() {
        shutdown()
        if (hangDetected) {
            for (thread in threads)
                thread.stop()
        }
    }

    class TestThread(val iThread: Int, val runnerHash: Int, runnable: Runnable) :
        Thread(runnable, "FixedActiveThreadsExecutor@$runnerHash-$iThread")
    {
        var cont: CancellableContinuation<*>? = null
    }

}

private const val SPINNING_LOOP_ITERATIONS_BEFORE_PARK = 1000_000

private val SHUTDOWN = "SHUTDOWN"
private val DONE = "DONE"