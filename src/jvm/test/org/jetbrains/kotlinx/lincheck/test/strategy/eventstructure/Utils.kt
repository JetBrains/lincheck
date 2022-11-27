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

package org.jetbrains.kotlinx.lincheck.test.strategy.eventstructure

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.verifier.*

import org.junit.Assert

private const val UNIQUE_OUTCOMES = -1

internal fun<Outcome> litmusTest(
    testClass: Class<*>,
    testScenario: ExecutionScenario,
    expectedOutcomes: Set<Outcome>,
    executionCount: Int = UNIQUE_OUTCOMES,
    getOutcome: (ExecutionResult) -> Outcome,
) {
    require(executionCount >= 0 || executionCount == UNIQUE_OUTCOMES)

    val outcomes: MutableSet<Outcome> = mutableSetOf()
    val verifier = createVerifier(testScenario) { results ->
        outcomes.add(getOutcome(results))
        true
    }
    val strategy = createStrategy(testClass, testScenario, verifier)
    val failure = strategy.run()
    assert(failure == null) { failure.toString() }
    Assert.assertEquals(expectedOutcomes, outcomes)

    val expectedCount = if (executionCount == UNIQUE_OUTCOMES)
        expectedOutcomes.size
        else executionCount
    Assert.assertEquals(expectedCount, strategy.stats.consistentInvocations)
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
            validationFunctions = listOf(),
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

        override fun checkStateEquivalenceImplementation(): Boolean {
            return true
        }

    }

internal inline fun<reified T> getValue(result: Result): T =
    (result as ValueResult).value as T
