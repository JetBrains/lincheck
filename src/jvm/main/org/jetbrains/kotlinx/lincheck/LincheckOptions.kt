/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTestConfiguration
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressStrategy
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*

import kotlin.math.*
import kotlin.reflect.*

interface LincheckOptions {
    /**
     * The maximal amount of time in seconds dedicated to testing.
     */
    var testingTimeInSeconds: Long

    /**
     * Maximal number of threads in generated scenarios.
     */
    var maxThreads: Int

    /**
     * Maximal number of operations in generated scenarios.
     */
    var maxOperationsInThread: Int

    /**
     * The verifier class used to check consistency of the execution.
     */
    var verifier: Class<out Verifier>

    /**
     * The specified class defines the sequential behavior of the testing data structure;
     * it is used by [Verifier] to build a labeled transition system,
     * and should have the same methods as the testing data structure.
     *
     * By default, the provided concurrent implementation is used in a sequential way.
     */
    var sequentialImplementation: Class<*>?

    /**
     * Set to `true` to check the testing algorithm for obstruction-freedom.
     * It also extremely useful for lock-free and wait-free algorithms.
     */
    var checkObstructionFreedom: Boolean

    /**
     * Add the specified custom scenario additionally to the generated ones.
     */
    fun addCustomScenario(scenario: ExecutionScenario)

    /**
     * Runs the Lincheck test on the specified class.
     *
     * @return [LincheckFailure] if some bug has been found.
     */
    fun checkImpl(testClass: Class<*>): LincheckFailure?
}

/**
 * Creates new instance of LincheckOptions class.
 */
fun LincheckOptions(): LincheckOptions = LincheckOptionsImpl()

fun LincheckOptions(configurationBlock: LincheckOptions.() -> Unit): LincheckOptions {
    val options = LincheckOptionsImpl()
    options.configurationBlock()
    return options
}

/**
 * Runs the Lincheck test on the specified class.
 *
 * @throws [LincheckAssertionError] if some bug has been found.
 */
fun LincheckOptions.check(testClass: Class<*>) {
    checkImpl(testClass)?.let { throw LincheckAssertionError(it) }
}

/**
 * Add the specified custom scenario additionally to the generated ones.
 */
fun LincheckOptions.addCustomScenario(scenarioBuilder: DSLScenarioBuilder.() -> Unit): Unit =
    addCustomScenario(scenario { scenarioBuilder() })

fun LincheckOptions.check(testClass: KClass<*>) = check(testClass.java)

// For internal tests only.
internal enum class LincheckMode {
    Stress, ModelChecking, Hybrid
}

internal class LincheckOptionsImpl : LincheckOptions {
    private val customScenarios = mutableListOf<ExecutionScenario>()

    override var testingTimeInSeconds = DEFAULT_TESTING_TIME
    override var maxThreads = DEFAULT_MAX_THREADS
    override var maxOperationsInThread = DEFAULT_MAX_OPERATIONS
    override var verifier: Class<out Verifier> = LinearizabilityVerifier::class.java
    override var sequentialImplementation: Class<*>? = null
    override var checkObstructionFreedom: Boolean = false

    internal var mode = LincheckMode.Hybrid
    internal var invocationTimeoutMs = CTestConfiguration.DEFAULT_TIMEOUT_MS
    internal var minimizeFailedScenario = true
    internal var generateScenarios = true
    internal var generateBeforeAndAfterParts = true

    override fun addCustomScenario(scenario: ExecutionScenario) {
        customScenarios.add(scenario)
    }

    override fun checkImpl(testClass: Class<*>): LincheckFailure? {
        val reporter = Reporter(DEFAULT_LOG_LEVEL)
        val planner = AdaptivePlanner(
            testingTime = testingTimeInSeconds * 1000,
            iterationsLowerBound = customScenarios.size
        )
        val testStructure = CTestStructure.getFromTestClass(testClass)
        val executionGenerator = RandomExecutionGenerator(testStructure)
        var verifier = createVerifier(testClass)
        while (planner.shouldDoNextIteration()) {
            val i = planner.iteration
            // For performance reasons, verifier re-uses LTS from previous iterations.
            // This behaviour is similar to a memory leak and can potentially cause OutOfMemoryError.
            // This is why we periodically create a new verifier to still have increased performance
            // from re-using LTS and limit the size of potential memory leak.
            // https://github.com/Kotlin/kotlinx-lincheck/issues/124
            if ((i + 1) % LinChecker.VERIFIER_REFRESH_CYCLE == 0) {
                verifier = createVerifier(testClass)
            }
            // TODO: maybe we should move custom scenarios logic into Planner?
            val isCustomScenario = (i < customScenarios.size)
            val scenario = if (isCustomScenario)
                customScenarios[i]
            else
                executionGenerator.nextExecution(maxThreads, maxOperationsInThread,
                    if (generateBeforeAndAfterParts) maxOperationsInThread else 0,
                    if (generateBeforeAndAfterParts) maxOperationsInThread else 0,
                )
            scenario.validate()
            reporter.logIteration(i, scenario)
            val currentMode = planner.currentMode()
            val strategy = createStrategy(currentMode, testClass, scenario, testStructure)
            val failure = planner.measureIterationTime {
                strategy.run(verifier, planner)
            }
            reporter.logIterationStatistics(planner.iterationsInvocationCount[i], planner.iterationsRunningTime[i])
            if (failure == null)
                continue
            // fix the number of invocations for failure minimization
            val minimizationInvocationsCount =
                max(2 * planner.iterationsInvocationCount[i], planner.invocationsBound)
            var minimizedFailure = if (!isCustomScenario)
                failure.minimize(reporter) {
                    createStrategy(currentMode, testClass, scenario, testStructure)
                        .run(verifier, FixedInvocationPlanner(minimizationInvocationsCount))
                }
            else
                failure
            if (mode == LincheckMode.Hybrid && currentMode == LincheckMode.Stress) {
                // try to reproduce an error trace with model checking strategy
                createStrategy(LincheckMode.ModelChecking, testClass, minimizedFailure.scenario, testStructure)
                    .run(verifier, FixedInvocationPlanner(MODEL_CHECKING_ON_ERROR_INVOCATIONS_COUNT))
                    ?.let { minimizedFailure = it }
            }
            reporter.logFailedIteration(minimizedFailure)
            return minimizedFailure
        }
        return null
    }

    private fun AdaptivePlanner.currentMode() = when (this@LincheckOptionsImpl.mode) {
            LincheckMode.Stress         -> LincheckMode.Stress
            LincheckMode.ModelChecking  -> LincheckMode.ModelChecking
            LincheckMode.Hybrid         -> {
                if (testingProgress < STRATEGY_SWITCH_THRESHOLD)
                    LincheckMode.Stress
                else
                    LincheckMode.ModelChecking
            }
        }

    private fun createVerifier(testClass: Class<*>) = verifier
        .getConstructor(Class::class.java)
        .newInstance(
            chooseSequentialSpecification(sequentialImplementation, testClass)
        )

    private fun createStrategy(
        mode: LincheckMode,
        testClass: Class<*>,
        scenario: ExecutionScenario,
        testStructure: CTestStructure,
    ): Strategy = when (mode) {

        LincheckMode.Stress -> StressStrategy(testClass, scenario,
            testStructure.validationFunctions,
            testStructure.stateRepresentation,
            timeoutMs = CTestConfiguration.DEFAULT_TIMEOUT_MS,
        )

        LincheckMode.ModelChecking -> ModelCheckingStrategy(testClass, scenario,
            testStructure.validationFunctions,
            testStructure.stateRepresentation,
            timeoutMs = CTestConfiguration.DEFAULT_TIMEOUT_MS,
            checkObstructionFreedom = ManagedCTestConfiguration.DEFAULT_CHECK_OBSTRUCTION_FREEDOM,
            eliminateLocalObjects = ManagedCTestConfiguration.DEFAULT_ELIMINATE_LOCAL_OBJECTS,
            hangingDetectionThreshold = ManagedCTestConfiguration.DEFAULT_HANGING_DETECTION_THRESHOLD,
            guarantees = ManagedCTestConfiguration.DEFAULT_GUARANTEES,
        )

        else -> throw IllegalArgumentException()
    }
}

private const val DEFAULT_TESTING_TIME = 5L
private const val DEFAULT_MAX_THREADS = 4
private const val DEFAULT_MAX_OPERATIONS = 4

// in hybrid mode: testing progress threshold (in %) after which strategy switch
//   from Stress to ModelChecking strategy occurs
private const val STRATEGY_SWITCH_THRESHOLD = 25

private const val MODEL_CHECKING_ON_ERROR_INVOCATIONS_COUNT = 10_000

