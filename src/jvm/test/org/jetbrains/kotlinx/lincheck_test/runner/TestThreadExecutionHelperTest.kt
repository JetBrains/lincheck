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
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.ExceptionResult.Companion.create
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.emptyScenario
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.withLincheckTestContext
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.util.*

class TestThreadExecutionHelperTest {
    private var runner: ExecutionScenarioRunner? = null

    @Before
    fun setUp() {
        val strategy: Strategy = object : Strategy() {
            override val runner: Runner get() {
                throw UnsupportedOperationException()
            }

            override fun runInvocation(): InvocationResult {
                throw UnsupportedOperationException()
            }
        }
        runner = ExecutionScenarioRunner(
            scenario = emptyScenario(),
            testClass = ArrayDeque::class.java,
            validationFunction = null,
            stateRepresentationFunction = null,
            timeoutMs = 0L,
            useClocks = UseClocks.ALWAYS,
        ).apply {
            initializeStrategy(strategy)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testBase() = withLincheckTestContext(InstrumentationMode.STRESS) {
        val ex = TestThreadExecutionGenerator.create(
            runner, 0,
            listOf(
                Actor(Queue::class.java.getMethod("add", Any::class.java), mutableListOf<Int?>(1)),
                Actor(Queue::class.java.getMethod("add", Any::class.java), mutableListOf<Int?>(2)),
                Actor(Queue::class.java.getMethod("remove"), emptyList<Any>()),
                Actor(Queue::class.java.getMethod("element"), emptyList<Any>()),
                Actor(Queue::class.java.getMethod("peek"), emptyList<Any>())
            ),
            emptyList(),
            false
        )
        ex.testInstance = ArrayDeque<Any>()
        ex.results = arrayOfNulls(5)
        ex.run()
        Assert.assertArrayEquals(
            arrayOf<Result>(
                ValueResult(true),
                ValueResult(true),
                ValueResult(1),
                ValueResult(2),
                ValueResult(2)
            ),
            ex.results
        )
    }


    @Test
    @Throws(Exception::class)
    fun testActorExceptionHandling() = withLincheckTestContext(InstrumentationMode.STRESS) {
        val ex = TestThreadExecutionGenerator.create(
            runner, 0,
            listOf(
                Actor(ArrayDeque::class.java.getMethod("addLast", Any::class.java), mutableListOf<Int?>(1)),
                Actor(Queue::class.java.getMethod("remove"), emptyList<Any>()),
                Actor(Queue::class.java.getMethod("remove"), emptyList<Any>()),
                Actor(Queue::class.java.getMethod("remove"), emptyList<Any>())
            ),
            emptyList(),
            false
        )
        ex.testInstance = ArrayDeque<Any>()
        ex.results = arrayOfNulls(4)
        ex.clocks = Array(4) { IntArray(1) }
        ex.allThreadExecutions = arrayOfNulls(1)
        ex.allThreadExecutions[0] = ex
        ex.run()
        Assert.assertArrayEquals(
            arrayOf(
                VoidResult,
                ValueResult(1),
                create(NoSuchElementException()),
                create(NoSuchElementException())
            ),
            ex.results
        )
    }
}