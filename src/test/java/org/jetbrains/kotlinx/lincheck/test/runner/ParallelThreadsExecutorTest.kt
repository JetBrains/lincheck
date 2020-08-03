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

import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsExecutor
import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner
import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution
import org.junit.Test
import java.util.concurrent.TimeoutException

class ParallelThreadsExecutorTest {
    @Test
    fun testSubmit() {
        val executor = ParallelThreadsExecutor(2, 0)
        val executed = arrayOf(false, false)
        val executions = Array<TestThreadExecution>(2) {
            object : TestThreadExecution() {
                override fun run() {
                    executed[it] = true
                }
            }
        }
        executor.submitAndAwaitExecutions(executions)
        check(executed.all { it })
        executor.shutdown()
    }

    @Test
    fun testResubmit() {
        val executor = ParallelThreadsExecutor(2, 0)
        val executed = arrayOf(false, false)
        val executions = Array<TestThreadExecution>(2) {
            object : TestThreadExecution() {
                override fun run() {
                    executed[it] = true
                }
            }
        }
        executor.submitAndAwaitExecutions(executions)
        executed.fill(false)
        executor.submitAndAwaitExecutions(executions)
        check(executed.all { it })
        executor.shutdown()
    }

    @Test
    fun testSubmitTimeout() {
        val executor = ParallelThreadsExecutor(2, 0)
        val executions = Array<TestThreadExecution>(2) {
            object : TestThreadExecution() {
                override fun run() {
                    if (it == 1)
                        while (true);
                }
            }
        }
        try {
            executor.submitAndAwaitExecutions(executions, 200)
        } catch (e: TimeoutException) {
            return // TimeoutException is expected
        }
        check(false) { "TimeoutException was expected" }
        executor.shutdown()
    }

    @Test(timeout = 100_000)
    fun testShutdown() {
        // executor with unique runner hash
        val uniqueRunnerHash = 1337
        val executor = ParallelThreadsExecutor(2, uniqueRunnerHash)
        executor.shutdown()
        while (true) {
            // check that all test threads are finished
            if (Thread.getAllStackTraces().keys.all { it !is ParallelThreadsRunner.TestThread || it.runnerHash != uniqueRunnerHash })
                return
        }
    }
}
