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

package org.jetbrains.kotlinx.lincheck.runner

import kotlin.native.concurrent.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import kotlin.math.*
import kotlin.native.ThreadLocal
import kotlin.native.internal.test.*
import kotlin.system.*

internal class TestThread constructor(val iThread: Int, runnerHash: Int) {
    val worker = AtomicReference(Worker.start(true, "Worker $iThread $runnerHash"))
    val runnableFuture = AtomicReference<Future<Any>?>(null)

    fun executeTask(r: () -> Any) {
        runnableFuture.value = worker.value.execute(TransferMode.UNSAFE, { r }, { it.invoke() })
    }

    fun awaitLastTask(deadline: Long): Any {
        while(deadline > currentTimeMillis()) {
            if (runnableFuture.value!!.state.value == FutureState.COMPUTED.value) {
                return runnableFuture.value!!.result
            }
        }
        return FixedActiveThreadsExecutor.TIMEOUT
    }

    fun terminate() {
        // Don't want to terminate threads because of GC. Don't want to wait for result because of hanging

        //printErr("stop() $iThread called")
        //val res = runnableFuture?.result
        //println("terminate $iThread start")
        //val result = runnableFuture!!.result
        //worker.execute(TransferMode.UNSAFE, { }, { sleep(1000000000) })
        //println("terminate $iThread end")
        //worker.value.requestTermination(false).result
        //return res
        //printErr("stop() $iThread finished")
    }
}

internal fun currentTimeMillis() = getTimeMillis()

/**
 * This executor maintains the specified number of threads and is used by
 * [ParallelThreadsRunner] to execute [ExecutionScenario]-s. The main feature
 * is that this executor keeps the re-using threads "hot" (active) as long as
 * possible, so that they should not be parked and unparked between invocations.
 */
internal class FixedActiveThreadsExecutor(private val nThreads: Int,
                                          runnerHash: Int,
                                          private val initThreadFunction: (() -> Unit)? = null,
                                          private val finishThreadFunction: (() -> Unit)? = null) {
    // Threads used in this runner.
    private val threads: LincheckAtomicArray<TestThread> = LincheckAtomicArray(nThreads)

    init {
        (0 until nThreads).forEach { iThread ->
            threads.array[iThread].value = TestThread(iThread, runnerHash).also {
                it.executeTask { initThreadFunction?.invoke(); Any() }
                it.awaitLastTask(currentTimeMillis() + 10000)
            }
        }
    }

    /**
     * Submits the specified set of [tasks] to this executor
     * and waits until all of them are completed.
     * The number of tasks should be equal to [nThreads].
     *
     * @throws LincheckTimeoutException if more than [timeoutMs] is passed.
     * @throws LincheckExecutionException if an unexpected exception is thrown during the execution.
     */
    fun submitAndAwait(tasks: Array<out Runnable>, timeoutMs: Long) {
        require(tasks.size == nThreads)
        submitTasks(tasks)
        await(timeoutMs)
    }

    private fun submitTasks(tasks: Array<out Any>) {
        val testThreads = Array<TestThread?>(nThreads){null}
        for (i in 0 until nThreads) {
            testThreads[i] = threads.array[i].value!! // Do atomic loads
        }
        testThreads.forEachIndexed{i, t -> t!!.executeTask { testThreadRunnable(i, tasks[i] as Runnable) }} // submit tasks
    }

    private fun await(timeoutMs: Long) {
        val deadline = currentTimeMillis() + timeoutMs
        for (iThread in 0 until nThreads)
            awaitTask(iThread, deadline)
    }

    private fun awaitTask(iThread: Int, deadline: Long) {
        val result = threads.array[iThread].value!!.awaitLastTask(deadline)
        if (result == TIMEOUT) throw LincheckTimeoutException()
        // Check whether there was an exception during the execution.
        if (result != DONE) throw LincheckExecutionException(result as Throwable)
    }

    private fun testThreadRunnable(iThread: Int, task: Runnable): Any {
        val runnable = task
        try {
            runnable.run()
        } catch (e: Throwable) {
            return wrapInvalidAccessFromUnnamedModuleExceptionWithDescription(e)
        }
        return DONE
    }

    fun close() {
        // submit the shutdown task.
        for (t in threads.toArray()) {
            t.executeTask { finishThreadFunction?.invoke(); Any() }
            t.terminate()
        }
    }

    companion object {
        internal val TIMEOUT = Any().freeze()
        private val DONE = Any()
    }
}
