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
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.withLincheckJavaAgent
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.util.*

class TestThreadExecutionHelperTest {
    private var runner: Runner? = null

    @Before
    fun setUp() {
        val scenario = ExecutionScenario(emptyList(), emptyList(), emptyList(), null)
        val strategy: Strategy = object : Strategy(scenario) {
            override val runner: Runner
                get() {
                    throw UnsupportedOperationException()
                }

            override fun runInvocation(): InvocationResult {
                throw UnsupportedOperationException()
            }
        }
        runner = object : Runner(
            strategy,
            ArrayDeque::class.java,
            null,
            null,
        ) {
            override fun isCoroutineResumed(iThread: Int, actorId: Int): Boolean {
                return false
            }

            override fun afterCoroutineCancelled(iThread: Int) {}

            override fun afterCoroutineResumed(iThread: Int) {}

            override fun afterCoroutineSuspended(iThread: Int) {}

            override fun onThreadFinish(iThread: Int) {}

            override fun onThreadStart(iThread: Int) {}

            override fun run(): InvocationResult {
                throw UnsupportedOperationException()
            }

            override fun isCurrentRunnerThread(thread: Thread): Boolean {
                return false
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun testBase() = withLincheckJavaAgent(InstrumentationMode.STRESS) {
        val ex = TestThreadExecutionGenerator.create(
            runner, 0,
            Arrays.asList(
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
    fun testActorExceptionHandling() = withLincheckJavaAgent(InstrumentationMode.STRESS) {
        val ex = TestThreadExecutionGenerator.create(
            runner, 0,
            Arrays.asList(
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