/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.runner

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.CTestConfiguration.Companion.DEFAULT_TIMEOUT_MS
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.runner.UseClocks.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck_test.verifier.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

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
        while (continuation.get() == null) {}
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
                        actor(susWithoutException), ExceptionResult.create(SuspendResumeScenarios.TestException(), wasSuspended = true)
                    )
                }
                thread {
                    operation(actor(resWithException), VoidResult)
                }
            }
        }
        ParallelThreadsRunner(
            strategy = mockStrategy(scenario), testClass = testClass, validationFunction = null,
            stateRepresentationFunction = null, useClocks = RANDOM, timeoutMs = DEFAULT_TIMEOUT_MS
        ).use { runner ->
            runner.initialize()
            val results = (runner.run() as CompletedInvocationResult).results
            assertTrue(results.equalsIgnoringClocks(expectedResults))
        }

    }

    @Test
    fun testThrowExceptionInFollowUp() {
        val (scenario, expectedResults) = scenarioWithResults {
            parallel {
                thread {
                    operation(
                        actor(susResumeThrow), ExceptionResult.create(SuspendResumeScenarios.TestException(), wasSuspended = true)
                    )
                }
                thread {
                    operation(actor(resSucc, 77), VoidResult)
                }
            }
        }
        ParallelThreadsRunner(
            strategy = mockStrategy(scenario), testClass = testClass, validationFunction = null,
            stateRepresentationFunction = null, useClocks = RANDOM, timeoutMs = DEFAULT_TIMEOUT_MS
        ).use { runner ->
            runner.initialize()
            val results = (runner.run() as CompletedInvocationResult).results
            assertTrue(results.equalsIgnoringClocks(expectedResults))
        }
    }

    @Test
    fun testThrow() {
        val (scenario, expectedResults) = scenarioWithResults {
            parallel {
                thread {
                    operation(actor(susThrow), ExceptionResult.create(SuspendResumeScenarios.TestException()))
                }
            }
        }
        ParallelThreadsRunner(
            strategy = mockStrategy(scenario), testClass = testClass, validationFunction = null,
            stateRepresentationFunction = null, useClocks = RANDOM, timeoutMs = DEFAULT_TIMEOUT_MS
        ).use { runner ->
            runner.initialize()
            val results = (runner.run() as CompletedInvocationResult).results
            assertTrue(results.equalsIgnoringClocks(expectedResults))
        }
    }
}

class ParallelThreadExecutionExceptionsTest {
    @Test
    fun `should fail with unexpected exception results because of classes are not accessible from unnamed modules`() {
        val scenario = scenario {
            parallel {
                thread { actor(::operation) }
            }
        }
        ParallelThreadsRunner(
            strategy = mockStrategy(scenario), testClass = this::class.java, validationFunction = null,
            stateRepresentationFunction = null, useClocks = RANDOM, timeoutMs = DEFAULT_TIMEOUT_MS
        ).use { runner ->
            runner.initialize()
            val results = (runner.run() as UnexpectedExceptionInvocationResult)
            val exception = results.exception

            assertTrue(results.exception is RuntimeException)
            assertEquals(ADD_OPENS_MESSAGE, exception.message)
        }
    }

    @Operation
    fun operation(): Nothing {
        throw IllegalAccessException("module java.base does not \"opens java.io\" to unnamed module")
    }
}

fun mockStrategy(scenario: ExecutionScenario) = object : Strategy(scenario) {
    override fun runInvocation(): InvocationResult = error("Not yet implemented")
}