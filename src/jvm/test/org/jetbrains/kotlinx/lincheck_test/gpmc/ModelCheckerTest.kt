/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import kotlin.reflect.*
import org.junit.Assert

internal fun modelCheckerTest(
    testClass: KClass<*>,
    testOperation: KFunction<*>,
    expectedOutcomes: Set<Any?> = setOf(),
    expectedExceptions: Set<KClass<out Throwable>> = setOf(),
    expectedFailure: KClass<out LincheckFailure>? = null,
    invocations: Int = DEFAULT_INVOCATIONS_COUNT,
) {
    val scenario = scenario {
        parallel { thread { actor(testOperation) } }
    }
    val verifier = CollectResultsVerifier()
    withLincheckJavaAgent(InstrumentationMode.MODEL_CHECKING) {
        val strategy = createStrategy(testClass.java, scenario)
        val failure = strategy.runIteration(invocations, verifier)
        if (expectedFailure != null) {
            assert(expectedFailure.isInstance(failure))
            return
        }
        assert(failure == null) { failure.toString() }
        if (expectedExceptions.isNotEmpty()) {
            // check that all expected exceptions are discovered
            expectedExceptions.forEach { exceptionClass ->
                Assert.assertTrue(verifier.exceptions.any { exceptionClass.isInstance(it) })
            }
            // check that each discovered exception is an instance of some expected exception class
            verifier.exceptions.forEach { exception ->
                Assert.assertTrue(expectedExceptions.any { it.isInstance(exception) })
            }
        } else {
            // check that there was no exception thrown
            if (verifier.exceptions.isNotEmpty()) {
                val message = StringBuilder().apply {
                    appendLine("Unexpected exceptions!")
                    for (exception in verifier.exceptions) {
                        appendLine("$exception ${exception.message?.let { ": $it" } ?: "" }")
                        appendLine(exception.stackTraceToString())
                    }
                }.toString()
                Assert.fail(message)
            }
        }
        Assert.assertEquals(expectedOutcomes, verifier.values)
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
        .invocationTimeout(5_000) // 5 sec
        .createTestConfigurations(testClass)

private class CollectResultsVerifier : Verifier {
    val values: MutableSet<Any?> = HashSet()
    val exceptions: MutableSet<Throwable> = HashSet()

    override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
        when (val result = results!!.parallelResults[0][0]!!) {
            is VoidActorResult -> {}
            is ValueActorResult -> {
                values.add(result.value)
            }
            is ExceptionActorResult -> {
                exceptions.add(result.throwable)
            }
            else -> {
                throw IllegalStateException("Unexpected result: $result")
            }
        }
        return true
    }
}

private const val DEFAULT_INVOCATIONS_COUNT = 100

internal const val TIMEOUT = 30_000L // 30 sec