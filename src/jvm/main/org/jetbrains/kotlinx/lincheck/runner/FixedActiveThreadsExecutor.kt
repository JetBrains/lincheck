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

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.runInsideIgnoredSection
import sun.nio.ch.lincheck.TestThread
import java.io.*
import java.lang.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.*

/**
 * This executor maintains the specified number of threads and is used by
 * [ParallelThreadsRunner] to execute [ExecutionScenario]-s. The main feature
 * is that this executor keeps the re-using threads "hot" (active) as long as possible,
 * so that they should not be parked and unparked between invocations.
 */
internal class FixedActiveThreadsExecutor(private val testName: String, private val nThreads: Int) : Closeable {
    /**
     * null, waiting TestThread, Runnable task, or SHUTDOWN
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
     *
     * Only the main thread submitting tasks manipulates this spinner.
     */
    // we set `nThreads + 1` as a number of threads, because
    // we have `nThreads` of the scenario plus the main thread waiting for the result;
    // if this number is greater than the number of available CPUs,
    // the main thread will be parked immediately without spinning;
    // in this case, if `nCPUs = nThreads` all the scenario threads still will be spinning
    private val resultSpinner = Spinner(nThreads + 1)

    /**
     * This flag is set to `true` when [await] detects a hang.
     * In this case, when this executor is closed, Thread.stop()
     * is called on all the internal threads.
     */
    private var hangDetected = false

    /**
     * Threads used in this runner.
     */
    val threads = Array(nThreads) { iThread ->
        TestThread(testName, iThread, testThreadRunnable(iThread)).also {
            it.start()
        }
    }

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
            submitTask(i, Shutdown)
    }

    private fun submitTask(iThread: Int, task: Any) {
        results[iThread] = null
        val old = tasks.getAndSet(iThread, task)
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
        val result = resultSpinner.spinWaitBoundedFor { results[iThread] }
        if (result != null) return result
        // Park with timeout until the result is set or the timeout is passed.
        val currentThread = Thread.currentThread()
        if (results.compareAndSet(iThread, null, currentThread)) {
            while (results[iThread] === currentThread) {
                val timeLeft = deadline - System.nanoTime()
                if (timeLeft <= 0) {
                    hangDetected = true
                    throw TimeoutException()
                }
                LockSupport.parkNanos(timeLeft)
            }
        }
        return results[iThread]
    }

    private fun testThreadRunnable(iThread: Int) = Runnable {
        loop@ while (true) {
            val task = runInsideIgnoredSection {
                val task = getTask(iThread)
                if (task === Shutdown) return@Runnable
                tasks[iThread] = null // reset task
                task as TestThreadExecution
            }
            check(task.iThread == iThread)
            try {
                task.run()
            } catch(e: Throwable) {
                runInsideIgnoredSection { setResult(iThread, e) }
                continue@loop
            }
            runInsideIgnoredSection { setResult(iThread, Done) }
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
        if (hangDetected && majorJavaVersion < 20) {
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
