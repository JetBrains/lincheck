/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.junit.Assert.assertEquals
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Test

class DynamicThreadsTest {

    private val counter = AtomicInteger(0)

    @Operation
    fun operation(): Int {
        val t1 = thread {
            counter.incrementAndGet()
        }
        val t2 = thread {
            counter.incrementAndGet()
        }
        val t3 = thread {
            counter.incrementAndGet()
        }
        return counter.get().also {
            t1.join()
            t2.join()
            t3.join()
        }
    }

    val scenario = scenario {
        parallel {
            thread {
                actor(::operation)
            }
        }
    }

    @Test
    fun test() {
        val verifier = CollectResultsVerifier()
        withLincheckJavaAgent(InstrumentationMode.MODEL_CHECKING) {
            val strategy = createStrategy(this::class.java, scenario)
            val failure = strategy.runIteration(
                invocations = 100,
                verifier = verifier,
            )
            assert(failure == null) { failure.toString() }
            assertEquals(setOf(0, 1, 2, 3), verifier.values)
        }
    }

}

private fun createStrategy(testClass: Class<*>, scenario: ExecutionScenario): ModelCheckingStrategy {
    return createConfiguration(testClass)
        .createStrategy(
            testClass = testClass,
            scenario = scenario,
            validationFunction = null,
            stateRepresentationMethod = null,
        ) as ModelCheckingStrategy
}

private fun createConfiguration(testClass: Class<*>) =
    ModelCheckingOptions()
        // for tests debugging set large timeout
        .invocationTimeout(60 * 60 * 1000)
        .createTestConfigurations(testClass)

private class CollectResultsVerifier : Verifier {
    val values: MutableSet<Any?> = HashSet()

    override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
        val result = results!!.parallelResults[0][0]!!
        val value = (result as ValueResult).value
        values.add(value)
        return true
    }
}