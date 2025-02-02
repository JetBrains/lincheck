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
import org.jetbrains.lincheck.datastructures.CTestConfiguration.Companion.DEFAULT_TIMEOUT_MS
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.runner.UseClocks.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.withLincheckTestContext
import org.jetbrains.kotlinx.lincheck_test.verifier.*
import org.jetbrains.lincheck.datastructures.actor
import org.jetbrains.lincheck.datastructures.scenario
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
        suspendCoroutineUninterceptedOrReturn<Int> { _ ->
            throw TestException()
        }
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
 * Test [ExecutionScenarioRunner] different suspend-resume scenarios with exceptions.
 */
class ExecutionScenarioRunnerExceptionTest {
    private val testClass = SuspendResumeScenarios::class.java

    private val susWithoutException = SuspendResumeScenarios::suspendWithoutException
    private val susThrow = SuspendResumeScenarios::suspendAndThrowException
    private val susResumeThrow = SuspendResumeScenarios::suspendAndThrowAfterResumption

    private val resWithException = SuspendResumeScenarios::resumeWithException
    private val resSucc = SuspendResumeScenarios::resumeSuccessfully

    @Test
    fun testResumeWithException() = withLincheckTestContext(InstrumentationMode.STRESS) {
        val (scenario, expectedResults) = scenarioWithResults {
            parallel {
                thread {
                    operation(
                        actor(susWithoutException), ExceptionResult.create(SuspendResumeScenarios.TestException())
                    )
                }
                thread {
                    operation(actor(resWithException), VoidResult)
                }
            }
        }
        ExecutionScenarioRunner(
            scenario = scenario,
            testClass = testClass,
            validationFunction = null,
            stateRepresentationFunction = null,
            timeoutMs = DEFAULT_TIMEOUT_MS,
            useClocks = RANDOM,
        ).use { runner ->
            runner.initializeStrategy(mockStrategy())
            val results = (runner.runInvocation() as CompletedInvocationResult).results
            assertTrue(results.equalsIgnoringClocks(expectedResults))
        }

    }

    @Test
    fun testThrowExceptionInFollowUp() = withLincheckTestContext(InstrumentationMode.STRESS) {
        val (scenario, expectedResults) = scenarioWithResults {
            parallel {
                thread {
                    operation(
                        actor(susResumeThrow), ExceptionResult.create(SuspendResumeScenarios.TestException())
                    )
                }
                thread {
                    operation(actor(resSucc, 77), VoidResult)
                }
            }
        }
        ExecutionScenarioRunner(
            scenario = scenario,
            testClass = testClass,
            validationFunction = null,
            stateRepresentationFunction = null,
            timeoutMs = DEFAULT_TIMEOUT_MS,
            useClocks = RANDOM,
        ).use { runner ->
            runner.initializeStrategy(mockStrategy())
            val results = (runner.runInvocation() as CompletedInvocationResult).results
            assertTrue(results.equalsIgnoringClocks(expectedResults))
        }
    }

    @Test
    fun testThrow() = withLincheckTestContext(InstrumentationMode.STRESS) {
        val (scenario, expectedResults) = scenarioWithResults {
            parallel {
                thread {
                    operation(actor(susThrow), ExceptionResult.create(SuspendResumeScenarios.TestException()))
                }
            }
        }
        ExecutionScenarioRunner(
            scenario = scenario,
            testClass = testClass,
            validationFunction = null,
            stateRepresentationFunction = null,
            timeoutMs = DEFAULT_TIMEOUT_MS,
            useClocks = RANDOM,
        ).use { runner ->
            runner.initializeStrategy(mockStrategy())
            val results = (runner.runInvocation() as CompletedInvocationResult).results
            assertTrue(results.equalsIgnoringClocks(expectedResults))
        }
    }
}

class ExecutionScenarioRunnerExceptionsTest {
    @Test
    fun shouldCompleteWithUnexpectedException() = withLincheckTestContext(InstrumentationMode.STRESS) {
        val scenario = scenario {
            parallel {
                thread { actor(::operation) }
            }
        }
        ExecutionScenarioRunner(
            scenario = scenario,
            testClass = this::class.java,
            validationFunction = null,
            stateRepresentationFunction = null,
            timeoutMs = DEFAULT_TIMEOUT_MS,
            useClocks = RANDOM,
        ).use { runner ->
            runner.initializeStrategy(mockStrategy())
            val result = runner.runInvocation()
            check(result is CompletedInvocationResult)
        }
    }

    @Operation
    fun operation(): Nothing {
        throw IllegalAccessException("unexpected exception")
    }
}

fun mockStrategy() = object : Strategy() {
    override val runner: Runner get() = error("Not yet implemented")
    override fun runInvocation(): InvocationResult = error("Not yet implemented")
}