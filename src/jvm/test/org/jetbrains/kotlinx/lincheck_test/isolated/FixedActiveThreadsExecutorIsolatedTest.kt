/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.isolated

import org.jetbrains.kotlinx.lincheck.runner.*
import org.junit.*
import sun.nio.ch.lincheck.TestThread
import java.util.concurrent.*

class FixedActiveThreadsExecutorIsolatedTest {
    @Test
    fun testSubmit() = FixedActiveThreadsExecutor(traceCollectionEnabled = false, "FixedActiveThreadsExecutorTest.testSubmit",2, 0).use { executor ->
        val executed = arrayOf(false, false)
        val tasks = Array<TestThreadExecution>(2) { iThread ->
            object : TestThreadExecution(iThread) {
                override fun run() {
                    executed[iThread] = true
                }
            }
        }
        executor.submitAndAwait(tasks, Long.MAX_VALUE / 2)
        check(executed.all { it })
    }

    @Test
    fun testResubmit() = FixedActiveThreadsExecutor(traceCollectionEnabled = false, "FixedActiveThreadsExecutorTest.testResubmit",2, 0).use { executor ->
        val executed = arrayOf(false, false)
        val tasks = Array<TestThreadExecution>(2) { iThread ->
            object : TestThreadExecution(iThread) {
                override fun run() {
                    executed[iThread] = true
                }
            }
        }
        executor.submitAndAwait(tasks, Long.MAX_VALUE / 2)
        executed.fill(false)
        executor.submitAndAwait(tasks, Long.MAX_VALUE / 2)
        check(executed.all { it })
    }

    @Test(timeout = 100_000)
    fun testSubmitTimeout() = FixedActiveThreadsExecutor(traceCollectionEnabled = false, "FixedActiveThreadsExecutorTest.testSubmitTimeout",2, 0).use { executor ->
        val tasks = Array<TestThreadExecution>(2) { iThread ->
            object : TestThreadExecution(iThread) {
                init {
                    this.iThread = iThread
                }

                override fun run() {
                    if (iThread == 1)
                        while (true);
                }
            }
        }
        try {
            executor.submitAndAwait(tasks, 200)
        } catch (e: TimeoutException) {
            return // TimeoutException is expected
        }
        check(false) { "TimeoutException was expected" }
    }

    @Test(timeout = 100_000)
    fun testShutdown() {
        // executor with unique runner hash
        val uniqueRunnerHash = 1337
        FixedActiveThreadsExecutor(traceCollectionEnabled = false, "FixedActiveThreadsExecutorTest.testResubmit", 2, uniqueRunnerHash).close()
        while (true) {
            // check that all test threads are finished
            if (Thread.getAllStackTraces().keys.all { it !is TestThread || it.runnerHash != uniqueRunnerHash })
                return
        }
    }
}
