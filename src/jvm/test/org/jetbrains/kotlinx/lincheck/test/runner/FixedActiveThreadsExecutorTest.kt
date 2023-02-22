/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.test.runner

import org.jetbrains.kotlinx.lincheck.runner.FixedActiveThreadsExecutor
import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution
import org.junit.Test
import sun.nio.ch.lincheck.TestThread
import java.util.concurrent.TimeoutException

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
            if (Thread.getAllStackTraces().keys.all { it !is TestThread })
                return
        }
    }
}
