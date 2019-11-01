/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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

import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner
import org.junit.Test
import kotlin.coroutines.intrinsics.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.test.verifier.*
import org.jetbrains.kotlinx.lincheck.util.Either
import org.junit.Assert.assertEquals
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.*

/**
 * Defines suspend-resume cases with exceptions.
 */
class SuspendResumeScenarios {
    var continuation = AtomicReference<Continuation<Int>>(null)

    @Throws(TestException::class)
    suspend fun suspendWithoutException(): Int {
        val res = suspendCoroutineUninterceptedOrReturn<Int> {
            continuation.set(it)
            COROUTINE_SUSPENDED
        }
        return res + 100
    }

    @Throws(TestException::class)
    suspend fun suspendAndThrowAfterResumption(): Int {
        val res = suspendCoroutineUninterceptedOrReturn<Int> { cont ->
            continuation.set(cont)
            COROUTINE_SUSPENDED
        }
        if (res < 100) throw TestException()
        return res + 100
    }

    @Throws(TestException::class)
    suspend fun suspendAndThrowException(): Int {
        val res = suspendCoroutineUninterceptedOrReturn<Int> { cont ->
            throw TestException()
        }
        if (res < 100) throw TestException()
        return res + 100
    }

    fun resumeWithException() {
        while (continuation.get() == null) {
        }
        continuation.get()!!.resumeWithException(TestException())
    }

    fun resumeSuccessfully(value: Int) {
        while (continuation.get() == null) {
        }
        continuation.get()!!.resumeWith(kotlin.Result.success(value))
    }

    class TestException : Throwable()
}

/**
 * Test [ParallelThreadsRunner] different suspend-resume scenarios with exceptions.
 */
class ParallelThreadsRunnerExceptionTest {

    val mockStrategy = object : Strategy(null, null, null) {
        override fun run(): TestReport? {
            throw UnsupportedOperationException()
        }
    }

    val testClass = SuspendResumeScenarios::class.java

    private val susWithoutException = SuspendResumeScenarios::suspendWithoutException
    private val susThrow = SuspendResumeScenarios::suspendAndThrowException
    private val susResumeThrow = SuspendResumeScenarios::suspendAndThrowAfterResumption

    private val resWithException = SuspendResumeScenarios::resumeWithException
    private val resSucc = SuspendResumeScenarios::resumeSuccessfully

    @Test
    fun testResumeWithException() {
        val (scenario, expectedResults) = scenarioWithResults {
            parallel {
                thread {
                    operation(
                        actor(susWithoutException), ExceptionResult(SuspendResumeScenarios.TestException::class.java, wasSuspended = true)
                    )
                }
                thread {
                    operation(actor(resWithException), VoidResult)
                }
            }
        }
        val runner =
            ParallelThreadsRunner(scenario, mockStrategy, testClass, null)
        val results = runner.run()
        assertEquals(results, Either.Value(expectedResults))
    }

    @Test
    fun testThrowExceptionInFollowUp() {
        val (scenario, expectedResults) = scenarioWithResults {
            parallel {
                thread {
                    operation(
                        actor(susResumeThrow), ExceptionResult(SuspendResumeScenarios.TestException::class.java, wasSuspended = true)
                    )
                }
                thread {
                    operation(actor(resSucc, 77), VoidResult)
                }
            }
        }
        val runner = ParallelThreadsRunner(scenario, mockStrategy, testClass, null)
        val results = runner.run()
        assertEquals(results, Either.Value(expectedResults))
    }

    @Test
    fun testThrow() {
        val (scenario, expectedResults) = scenarioWithResults {
            parallel {
                thread {
                    operation(actor(susThrow), ExceptionResult(SuspendResumeScenarios.TestException::class.java))
                }
            }
        }
        val runner =
            ParallelThreadsRunner(scenario, mockStrategy, testClass, null)
        val results = runner.run()
        assertEquals(results, Either.Value(expectedResults))
    }
}



