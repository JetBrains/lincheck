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

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.LambdaRunner
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategySettings
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.strategy.runIteration
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.withLincheckTestContext
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation.ensureObjectIsTransformed
import org.junit.Assert
import org.jetbrains.kotlinx.lincheck.runner.ExecutionScenarioRunner

internal const val UNIQUE = -1
internal const val UNKNOWN = -2


//TODO: Maybe we can make a class out of this, instead of this function nonsense
typealias OutcomeVerifier<Outcome> = (Set<Outcome>, Int) -> Unit
internal fun <Outcome> assertNever(forbiddenOutcomes: Set<Outcome>): OutcomeVerifier<Outcome> = { actualOutcomes, invocations ->
    val overlap = forbiddenOutcomes.intersect(actualOutcomes)
    Assert.assertEquals("Forbidden outcomes detected: $overlap", overlap.size, 0)
}

internal fun <Outcome> assertSometimes(expectedOutcomes: Set<Outcome>): OutcomeVerifier<Outcome> = { actualOutcomes, invocations ->
    val missing = expectedOutcomes - actualOutcomes
    Assert.assertEquals("Some outcomes not detected:\n$missing\nGot:$actualOutcomes", missing.size, 0)
}

internal fun <Outcome> assertAlways(expectedOutcomes: Set<Outcome>, executionCount: Int = UNIQUE): OutcomeVerifier<Outcome> {
    require(executionCount >= 0 || executionCount == UNIQUE || executionCount == UNKNOWN)
    return { actualOutcomes, invocations ->
        val missing = expectedOutcomes - actualOutcomes
        val unexpected = actualOutcomes - expectedOutcomes
        val msg = "Some outcomes not detected.\nMissing:$missing\nUnexpected:$unexpected:\n"
        Assert.assertEquals(msg, expectedOutcomes, actualOutcomes)

        val expectedCount = when (executionCount) {
            UNIQUE -> expectedOutcomes.size
            UNKNOWN -> invocations
            else -> executionCount
        }
        Assert.assertEquals(expectedCount, invocations)
    }
}

/**
 * Litmus testing function for [EventStructureStrategy] that uses [ExecutionScenarioRunner].
 *
 * @param testClass class that is going to be tested by the execution scenario
 * @param testScenario the execution scenario that we are going to evaluate with the event structure strategy
 * @param expectedOutcomes the set of expected outcomes from running the test scenario. It has to match exactly as
 * the set of outcomes gathered from the execution.
 * @param executionCount the number of consistent invocations that should be found after running the testScenario
 *  If it is set to UNIQUE, then this number is equal to the size of [expectedOutcomes].
 *  If it is set to UNKNOWN, then we do not check the consistent invocations count.
 *  @param getOutcome the function that extracts the outcome from an [ExecutionResult].
 */
internal fun<Outcome> litmusTest(
    testClass: Class<*>,
    testScenario: ExecutionScenario,
    outcomeVerifier: OutcomeVerifier<Outcome>,
    getOutcome: (ExecutionResult) -> Outcome,
) {
    val outcomes: MutableSet<Outcome> = mutableSetOf()
    val verifier = getResultsVerifier { results ->
        outcomes.add(getOutcome(results))
        true
    }
    withLincheckTestContext(InstrumentationMode.EXPERIMENTAL_MODEL_CHECKING) {
        val strategy = createStrategy(testClass, testScenario)
        val failure = strategy.runIteration(INVOCATIONS, verifier)
        assert(failure == null) { failure.toString() }
        outcomeVerifier(outcomes, strategy.stats.consistentInvocations)
    }
}

private fun createConfiguration(testClass: Class<*>) =
    ModelCheckingOptions()
        .useExperimentalModelChecking()
        // for tests debugging set large timeout
        .invocationTimeout(60 * 60 * 1000)
        .createTestConfigurations(testClass)

internal fun createStrategy(testClass: Class<*>, scenario: ExecutionScenario): EventStructureStrategy {
    return createConfiguration(testClass)
        .createStrategy(
            testClass = testClass,
            scenario = scenario,
            validationFunction = null,
            stateRepresentationMethod = null,
        ) as EventStructureStrategy
}

internal inline fun<reified T> getValue(result: LincheckResult): T =
    (result as ValueResult).value as T

internal fun getValueSuspended(result: LincheckResult): Any? = when (result) {
    is ValueResult -> result.value
    is ExceptionResult -> result.throwable
    is SuspendedResult -> result
    is CancelledResult -> result
    else -> throw IllegalArgumentException()
}

internal fun getResultsVerifier(verify: (ExecutionResult) -> Boolean): Verifier =
    object : Verifier {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            require(results != null)
            results.parallelResults.flatten().forEach {
                if (it is ExceptionResult) {
                    throw it.throwable
                }
            }
            return verify(results)
        }
    }


internal fun <T> createStrategy(
    timeoutMs: Long,
    settings: ManagedStrategySettings,
    inIdeaPluginReplayMode: Boolean = false,
    block: () -> T
): EventStructureStrategy {
    val runner = LambdaRunner(timeoutMs = timeoutMs, block)
    return EventStructureStrategy(runner, settings, inIdeaPluginReplayMode, LincheckInstrumentation.context).also {
        runner.initializeStrategy(it)
    }
}

//TODO: once using EventStructure model checker with LambdaRunner is more stable, we need to remove the other litmusTest overload
/**
 * Alternate litmus testing function for [EventStructureStrategy] that uses [LambdaRunner].
 *
 * @param expectedOutcomes the set of expected outcomes from running the test scenario. It has to match exactly as
 * the set of outcomes gathered from the execution.
 * @param executionCount the number of consistent invocations that should be found after running the testScenario
 *  If it is set to UNIQUE, then this number is equal to the size of [expectedOutcomes].
 *  If it is set to UNKNOWN, then we do not check the consistent invocations count.
 * @param block the block of code that is going to be tested
 */
internal inline fun<reified Outcome> litmusTest(
    outcomeVerifier: OutcomeVerifier<Outcome>,
    noinline block: () -> Outcome,
) {
    val INVOCATIONS = 10000
    val options = ModelCheckingOptions().analyzeStdLib(true)
    val testCfg = options.createTestConfigurations(block::class.java)
    val outcomes: MutableSet<Outcome> = mutableSetOf()
    val verifier = getResultsVerifier { result ->
        val value = getValue<Outcome>(result.parallelResults[0][0]!!)
        outcomes.add(value)
        true
    }
    withLincheckTestContext(InstrumentationMode.EXPERIMENTAL_MODEL_CHECKING) {
        ensureObjectIsTransformed(block)
        createStrategy(testCfg.timeoutMs, testCfg.createSettings(), testCfg.inIdeaPluginReplayMode, block).use { strategy ->
            val failure = strategy.runIteration(INVOCATIONS, verifier)
            assert(failure == null) { failure.toString() }
            outcomeVerifier(outcomes, strategy.stats.consistentInvocations)
        }
    }
}


internal const val TIMEOUT = 30 * 1000L // 30 sec

// we expect for all litmus tests to have less than 1000 outcomes
private const val INVOCATIONS = 1000