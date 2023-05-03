/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.runner

import org.jetbrains.kotlinx.lincheck.runner.*
import org.junit.*
import java.util.concurrent.*

class FixedActiveThreadsExecutorTest {
    @Test
    fun testSubmit() = FixedActiveThreadsExecutor(2, 0).use { executor ->
        val executed = arrayOf(false, false)
        val tasks = Array<TestThreadExecution>(2) {
            object : TestThreadExecution() {
                override fun run() {
                    executed[it] = true
                }
            }
        }
        executor.submitAndAwait(tasks, Long.MAX_VALUE / 2)
        check(executed.all { it })
    }

    @Test
    fun testResubmit() = FixedActiveThreadsExecutor(2, 0).use { executor ->
        val executed = arrayOf(false, false)
        val tasks = Array<TestThreadExecution>(2) {
            object : TestThreadExecution() {
                override fun run() {
                    executed[it] = true
                }
            }
        }
        executor.submitAndAwait(tasks, Long.MAX_VALUE / 2)
        executed.fill(false)
        executor.submitAndAwait(tasks, Long.MAX_VALUE / 2)
        check(executed.all { it })
    }

    @Test(timeout = 100_000)
    fun testSubmitTimeout() = FixedActiveThreadsExecutor(2, 0).use { executor ->
        val tasks = Array<TestThreadExecution>(2) { iThread ->
            object : TestThreadExecution() {
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
        FixedActiveThreadsExecutor(2, uniqueRunnerHash).close()
        while (true) {
            // check that all test threads are finished
            if (Thread.getAllStackTraces().keys.all { it !is FixedActiveThreadsExecutor.TestThread || it.runnerHash != uniqueRunnerHash })
                return
        }
    }
}
