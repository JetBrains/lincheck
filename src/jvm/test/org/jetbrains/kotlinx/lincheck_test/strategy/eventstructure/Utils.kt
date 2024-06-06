/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.transformation.withLincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.verifier.*

import org.junit.Assert

internal const val UNIQUE = -1
internal const val UNKNOWN = -2

internal fun<Outcome> litmusTest(
    testClass: Class<*>,
    testScenario: ExecutionScenario,
    expectedOutcomes: Set<Outcome>,
    executionCount: Int = UNIQUE,
    getOutcome: (ExecutionResult) -> Outcome,
) {
    require(executionCount >= 0 || executionCount == UNIQUE || executionCount == UNKNOWN)

    val outcomes: MutableSet<Outcome> = mutableSetOf()
    val verifier = createVerifier(testScenario) { results ->
        outcomes.add(getOutcome(results))
        true
    }
    withLincheckJavaAgent(InstrumentationMode.EXPERIMENTAL_MODEL_CHECKING) {
        val strategy = createStrategy(testClass, testScenario, verifier)
        val failure = strategy.run()
        assert(failure == null) { failure.toString() }
        Assert.assertEquals(expectedOutcomes, outcomes)

        val expectedCount = when (executionCount) {
            UNIQUE -> expectedOutcomes.size
            UNKNOWN -> strategy.stats.consistentInvocations
            else -> executionCount
        }
        Assert.assertEquals(expectedCount, strategy.stats.consistentInvocations)
    }
}

private fun createConfiguration(testClass: Class<*>) =
    EventStructureOptions()
        // for tests debugging set large timeout
        .invocationTimeout(60 * 60 * 1000)
        .createTestConfigurations(testClass)

internal fun createStrategy(testClass: Class<*>, scenario: ExecutionScenario, verifier: Verifier): EventStructureStrategy {
    return createConfiguration(testClass)
        .createStrategy(
            testClass = testClass,
            scenario = scenario,
            verifier = verifier,
            validationFunction = null,
            stateRepresentationMethod = null,
        )
}

internal fun createVerifier(testScenario: ExecutionScenario?, verify: (ExecutionResult) -> Boolean): Verifier =
    object : Verifier {

        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            require(testScenario == scenario)
            require(results != null)
            return verify(results)
        }

    }

internal inline fun<reified T> getValue(result: Result): T =
    (result as ValueResult).value as T

internal fun getValueSuspended(result: Result): Any? = when (result) {
    is ValueResult -> result.value
    is ExceptionResult -> result.throwable
    is Suspended -> result
    is Cancelled -> result
    else -> throw IllegalArgumentException()
}

internal const val TIMEOUT = 30 * 1000L // 30 sec