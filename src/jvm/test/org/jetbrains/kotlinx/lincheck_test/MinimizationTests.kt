/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

class MinimizationTest {
    @Volatile
    private var counter = 0

    @Operation
    fun inc() = counter++

    /* This test should find a bug in counter implementation but do not attempt to minimize it.
     */
    @Test
    fun testWithoutMinimization() {
        val options = LincheckOptions {
            this as LincheckOptionsImpl
            mode = LincheckMode.Stress
            minThreads = 4
            maxThreads = 4
            minOperationsInThread = 4
            maxOperationsInThread = 4
            minimizeFailedScenario = false
            tryReproduceTrace = false
        }
        try {
            options.check(MinimizationTest::class.java)
            fail("Should fail with LincheckAssertionError")
        } catch (error: LincheckAssertionError) {
            val failedScenario = error.failure.scenario
            assertTrue("The init part should NOT be minimized", failedScenario.initExecution.isNotEmpty())
            assertTrue("The post part should NOT be minimized", failedScenario.postExecution.isNotEmpty())
            assertEquals("The parallel part should NOT be minimized", 4, failedScenario.parallelExecution.size)
            for (i in failedScenario.parallelExecution.indices) {
                assertEquals("The parallel part should NOT be minimized", 4, failedScenario.parallelExecution[i].size)
            }
        }
    }

    /* This test should find a bug in counter implementation and correctly minimize it
     * into a scenario with two parallel threads with single `inc()` operation in each one.
     */
    @Test
    fun testWithMinimization() {
        val options = LincheckOptions {
            this as LincheckOptionsImpl
            mode = LincheckMode.Stress
            minThreads = 4
            maxThreads = 4
            minOperationsInThread = 4
            maxOperationsInThread = 4
            tryReproduceTrace = false
        }
        try {
            options.check(MinimizationTest::class.java)
            fail("Should fail with LincheckAssertionError")
        } catch (error: LincheckAssertionError) {
            val failedScenario = error.failure.scenario
            assertTrue("The init part should be minimized", failedScenario.initExecution.isEmpty())
            assertTrue("The post part should be minimized", failedScenario.postExecution.isEmpty())
            assertEquals("The error should be reproduced with only two threads",
                2, failedScenario.parallelExecution.size)
            for (i in failedScenario.parallelExecution.indices) {
                assertEquals("The error should be reproduced with one operation per thread (Thread #${i+1})",
                    1, failedScenario.parallelExecution[i].size)
            }
        }
    }
}

class MinimizationWithExceptionTest {

    /* This test should find a bug in counter implementation and correctly minimize it
     * into a scenario with two parallel threads with single `inc()` operation in each one;
     * the thrown exception in `exception` operation should not be reported as a bug
     * (because sequential specification behaves similarly),
     * and the `exception` operation should not be present in the resulting minimized scenario.
     */
    @Test
    fun testWithExpectedException() {
        val options = LincheckOptions {
            this as LincheckOptionsImpl
            mode = LincheckMode.Stress
            minThreads = 4
            maxThreads = 4
            minOperationsInThread = 4
            maxOperationsInThread = 4
            tryReproduceTrace = false
        }
        try {
            options.check(IncorrectImplementationWithException::class.java)
            fail("Should fail with LincheckAssertionError")
        } catch (error: LincheckAssertionError) {
            val failedScenario = error.failure.scenario
            assertTrue("The init part should be minimized", failedScenario.initExecution.isEmpty())
            assertTrue("The post part should be minimized", failedScenario.postExecution.isEmpty())
            assertEquals("The error should be reproduced with only two threads",
                2, failedScenario.parallelExecution.size)
            for (i in failedScenario.parallelExecution.indices) {
                assertEquals("The error should be reproduced with one operation per thread (Thread #${i+1})",
                    1, failedScenario.parallelExecution[i].size)
                val actor = failedScenario.parallelExecution[i][0]
                assertEquals("The error should be reproduced using only `inc()` operations",
                    "inc", actor.method.name)
            }
        }
    }

    /* This test should find an unexpected exception in `exception()` operation,
     * (unexpected, because it is not thrown in the sequential implementation),
     * and correctly minimize it into a scenario with single thread with single `exception()` operation.
     */
    @Test
    fun testWithUnexpectedException() {
        val options = LincheckOptions {
            this as LincheckOptionsImpl
            mode = LincheckMode.Stress
            minThreads = 4
            maxThreads = 4
            minOperationsInThread = 4
            maxOperationsInThread = 4
            tryReproduceTrace = false
            sequentialImplementation = SequentialImplementation::class.java
        }
        try {
            options.check(CorrectImplementationWithException::class.java)
            fail("Should fail with LincheckAssertionError")
        } catch (error: LincheckAssertionError) {
            val failedScenario = error.failure.scenario
            assertTrue("The init part should be minimized", failedScenario.initExecution.isEmpty())
            assertTrue("The post part should be minimized", failedScenario.postExecution.isEmpty())
            assertEquals("The error should be reproduced with only one thread",
                1, failedScenario.parallelExecution.size)
            assertEquals("The error should be reproduced with only one operation",
                1, failedScenario.parallelExecution[0].size)
            val actor = failedScenario.parallelExecution[0][0]
            assertEquals("The error should be reproduced using only `exception()` operation",
                "exception", actor.method.name)
        }
    }

    class IncorrectImplementationWithException {
        private var counter = 0

        @Operation
        fun inc() = counter++

        @Operation
        fun exception(): Unit = throw IllegalStateException()
    }

    class CorrectImplementationWithException {
        private val counter = AtomicInteger(0)

        @Operation
        fun inc() = counter.getAndIncrement()

        @Operation
        fun exception(): Unit = throw IllegalStateException()
    }

    class SequentialImplementation {
        private var counter = 0
        fun inc() = counter++
        fun exception() = Unit
    }
}