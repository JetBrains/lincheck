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

import sun.nio.ch.lincheck.TestThread
import org.jetbrains.lincheck.util.*
import org.jetbrains.kotlinx.lincheck.util.*
import java.io.*
import java.lang.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.*

/**
 * A thread pool executor designed for executing tasks on dedicated threads
 * with active spin-wait mechanisms to reduce latency in task execution.
 * Internal threads are pre-spawned and managed to execute submitted tasks.
 *
 * @param testName The name of the test for identifying the executor's purpose.
 * @param nThreads The number of threads managed by this executor.
 */
internal class ActiveThreadPoolExecutor(private val testName: String, private val nThreads: Int) : Closeable {
    /**
     * null, waiting Runnable task, or SHUTDOWN
     */
    private val tasks = AtomicReferenceArray<Any>(nThreads)

    /**
     * Spinners for spin-wait on tasks.
     *
     * Each thread of the executor manipulates its own spinner.
     */
    private val taskSpinners = SpinnerGroup(nThreads)

    /**
     * null, waiting in [submitAndAwait] thread, DONE, or exception
     */
    private val results = AtomicReferenceArray<Any>(nThreads)

    /**
     * Spinner for spin-wait on results.
     * Only the main thread submitting tasks manipulates this spinner.
     *
     * We set `nThreads + 1` as a number of threads, because
     * we have `nThreads` of the scenario plus the main thread waiting for the result.
     * If this number is greater than the number of available CPUs,
     * the main thread will be parked immediately without spinning;
     * in this case, if `nCPUs = nThreads` all the scenario threads still be spinning.
     */
    private val resultSpinner = Spinner(nThreads + 1)

    /**
     * This flag is set to `true` if one of the submitted tasks hung.
     * After this, the executor is considered to be poisoned.
     * Any subsequent attempt to submit new tasks
     * via [submitAndAwait] will raise [IllegalStateException].
     *
     * In case when this flag is set, when this executor is closed,
     * the [Thread.stop] method is called on all the internal threads
     * as a last resort measure to try stopping them and free the associated resources.
     */
    var isStuck: Boolean = false
        private set

    /**
     * Threads used in this runner.
     */
    val threads = List(nThreads) { iThread ->
        TestThread(testName, iThread, testThreadRunnable(iThread)).also {
            it.start()
        }
    }

    /**
     * Submits a collection of tasks to specific threads for execution
     * and waits for their completion within the specified timeout period.
     *
     * @param tasks a map where each key represents the thread index
     *   and the value is the task to be run on that thread.
     *   All thread indices in the map must be within the bounds of the current executor.
     * @param timeoutNano the maximum time to wait for task completion, in nanoseconds.
     * @return the total time taken to wait for the tasks to complete, in nanoseconds.
     * @throws IllegalArgumentException if any thread index in the tasks' map is outside the executor bounds.
     * @throws TimeoutException if more than [timeoutNano] is passed.
     * @throws ExecutionException if any of the submitted tasks throws an exception during execution.
     */
    fun submitAndAwait(tasks: ThreadMap<Runnable>, timeoutNano: Long): Long {
        require(tasks.keys.all { it in 0 until nThreads}) {
            "Submitted tasks contain thread index outside of current executor bounds."
        }
        check(!isStuck) {
            "This executor is stuck and cannot accept any new tasks."
        }
        submitTasks(tasks)
        return await(tasks, timeoutNano)
    }

    private fun submitTasks(tasks: ThreadMap<Runnable>) {
        for ((threadId, task) in tasks) {
            submitTask(threadId, task)
        }
    }

    private fun shutdown() {
        // submit the shutdown tasks
        for (threadId in 0 until nThreads) {
            submitTask(threadId, Shutdown)
        }
    }

    private fun submitTask(threadId: Int, task: Any) {
        results[threadId] = null
        val old = tasks.getAndSet(threadId, task)
        if (old is TestThread) {
            LockSupport.unpark(old)
        }
    }

    private fun await(tasks: ThreadMap<Runnable>, timeoutNano: Long): Long {
        val startTime = System.nanoTime()
        val deadline = startTime + timeoutNano
        var exception: Throwable? = null
        for ((threadId, _) in tasks) {
            val e = awaitTask(threadId, deadline)
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
        val result = resultSpinner.spinWaitBoundedFor { results[iThread] }
        if (result != null) return result
        // Park with timeout until the result is set or the timeout is passed.
        val currentThread = Thread.currentThread()
        if (results.compareAndSet(iThread, null, currentThread)) {
            while (results[iThread] === currentThread) {
                val timeLeft = deadline - System.nanoTime()
                if (timeLeft <= 0) {
                    isStuck = true
                    throw TimeoutException()
                }
                LockSupport.parkNanos(timeLeft)
            }
        }
        return results[iThread]
    }

    private fun testThreadRunnable(threadId: Int) = Runnable {
        loop@ while (true) {
            val task = runInsideIgnoredSection {
                val task = getTask(threadId)
                if (task === Shutdown) return@Runnable
                tasks[threadId] = null // reset task
                task as Runnable
            }
            try {
                task.run()
            } catch(e: Throwable) {
                runInsideIgnoredSection { setResult(threadId, e) }
                continue@loop
            }
            runInsideIgnoredSection { setResult(threadId, Done) }
        }
    }

    private fun getTask(iThread: Int): Any {
        // Active wait for a task for the limited number of loop cycles.
        val task = taskSpinners[iThread].spinWaitBoundedFor { tasks[iThread] }
        if (task != null) return task
        // Park until a task is stored into `tasks[iThread]`.
        val currentThread = Thread.currentThread()
        if (tasks.compareAndSet(iThread, null, currentThread)) {
            while (tasks[iThread] === currentThread) {
                LockSupport.park()
            }
        }
        return tasks[iThread]
    }

    private fun setResult(iThread: Int, any: Any) {
        if (results.compareAndSet(iThread, null, any)) return
        // CAS failed => a test thread is parked.
        // Set the result and unpark the waiting thread.
        val thread = results[iThread] as Thread
        results[iThread] = any
        LockSupport.unpark(thread)
    }

    override fun close() {
        shutdown()
        // Thread.stop() throws UnsupportedOperationException
        // starting from Java 20 and is removed in Java 26.
        if (isStuck && majorJavaVersion < 20) {
            threads.forEach {
                it::class.java.getMethod("stop").invoke(it)
            }
        }
    }

}

private val majorJavaVersion = System.getProperty("java.specification.version").removePrefix("1.").toInt()

// These constants are objects for easier debugging.
private object Shutdown
private object Done
