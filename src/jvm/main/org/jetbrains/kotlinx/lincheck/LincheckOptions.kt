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
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
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

    private val runningCustomScenarios: Boolean
        get() = customScenarios.isNotEmpty()

    override fun addCustomScenario(scenario: ExecutionScenario) {
        customScenarios.add(scenario)
    }

    override fun checkImpl(testClass: Class<*>): LincheckFailure? = when (mode) {
        LincheckMode.Stress         -> checkImplWithMode(LincheckMode.Stress, testingTimeInSeconds * 1000, testClass)
        LincheckMode.ModelChecking  -> checkImplWithMode(LincheckMode.ModelChecking, testingTimeInSeconds * 1000, testClass)
        LincheckMode.Hybrid         -> run {
            val stressTimeMs = (STRATEGY_SWITCH_THRESHOLD * testingTimeInSeconds * 1000).toLong()
            val modelCheckingTimeMs = ((1 - STRATEGY_SWITCH_THRESHOLD) * testingTimeInSeconds * 1000).toLong()
            checkImplWithMode(LincheckMode.Stress, stressTimeMs, testClass)?.let { return it }
            checkImplWithMode(LincheckMode.ModelChecking, modelCheckingTimeMs, testClass)?.let { return it }
            return null
        }
    }

    private fun checkImplWithMode(mode: LincheckMode, testingTimeMs: Long, testClass: Class<*>): LincheckFailure? {
        val testStructure = CTestStructure.getFromTestClass(testClass)
        val reporter = Reporter(DEFAULT_LOG_LEVEL)
        var verifier = createVerifier(testClass)
        val planner = createPlanner(mode, testingTimeMs, testStructure)
        var failure = planner.runIterations { i, scenario, invocationsPlanner ->
            // For performance reasons, verifier re-uses LTS from previous iterations.
            // This behaviour is similar to a memory leak and can potentially cause OutOfMemoryError.
            // This is why we periodically create a new verifier to still have increased performance
            // from re-using LTS and limit the size of potential memory leak.
            // https://github.com/Kotlin/kotlinx-lincheck/issues/124
            if ((i + 1) % LinChecker.VERIFIER_REFRESH_CYCLE == 0) {
                verifier = createVerifier(testClass)
            }
            scenario.validate()
            reporter.logIteration(i + 1, scenario)
            scenario.run(mode, testClass, testStructure, verifier, invocationsPlanner).also {
                reporter.logIterationStatistics(i, planner.iterationsPlanner as AdaptivePlanner)
            }
        } ?: return null
        if (minimizeFailedScenario && !runningCustomScenarios) {
            failure = failure.minimize(reporter) {
                it.run(mode, testClass, testStructure,
                    createVerifier(testClass),
                    FixedInvocationsPlanner(MINIMIZATION_INVOCATIONS_COUNT)
                )
            }
        }
        if (this.mode == LincheckMode.Hybrid && mode == LincheckMode.Stress) {
            // try to reproduce an error trace with model checking strategy
            failure.scenario.run(
                LincheckMode.ModelChecking,
                testClass, testStructure,
                createVerifier(testClass),
                FixedInvocationsPlanner(MODEL_CHECKING_ON_ERROR_INVOCATIONS_COUNT)
            )?.let {
                failure = it
            }
        }
        reporter.logFailedIteration(failure)
        return failure
    }

    private fun ExecutionScenario.run(
        currentMode: LincheckMode,
        testClass: Class<*>,
        testStructure: CTestStructure,
        verifier: Verifier,
        planner: InvocationsPlanner,
    ): LincheckFailure? =
        createStrategy(currentMode, testClass, this, testStructure).use {
            it.run(verifier, planner)
        }

    private fun Reporter.logIterationStatistics(iteration: Int, planner: AdaptivePlanner) {
        logIterationStatistics(planner.iterationsInvocationCount[iteration], planner.iterationsRunningTime[iteration])
    }

    private fun createPlanner(mode: LincheckMode, testingTimeMs: Long, testStructure: CTestStructure): Planner {
        return if (runningCustomScenarios) {
            FixedScenariosAdaptivePlanner(mode, testingTimeMs, customScenarios)
        } else {
            RandomScenariosAdaptivePlanner(mode, testingTimeMs,
                minThreads = DEFAULT_MIN_THREADS,
                maxThreads = maxThreads,
                minOperations = DEFAULT_MIN_OPERATIONS,
                maxOperations = maxOperationsInThread,
                generateBeforeAndAfterParts = generateBeforeAndAfterParts,
                scenarioGenerator = RandomExecutionGenerator(testStructure)
            )
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
            timeoutMs = invocationTimeoutMs,
        )

        LincheckMode.ModelChecking -> ModelCheckingStrategy(testClass, scenario,
            testStructure.validationFunctions,
            testStructure.stateRepresentation,
            timeoutMs = invocationTimeoutMs,
            checkObstructionFreedom = checkObstructionFreedom,
            eliminateLocalObjects = ManagedCTestConfiguration.DEFAULT_ELIMINATE_LOCAL_OBJECTS,
            hangingDetectionThreshold = ManagedCTestConfiguration.DEFAULT_HANGING_DETECTION_THRESHOLD,
            guarantees = ManagedCTestConfiguration.DEFAULT_GUARANTEES,
        )

        else -> throw IllegalArgumentException()
    }
}

private const val DEFAULT_TESTING_TIME = 5L
private const val DEFAULT_MIN_THREADS = 2
private const val DEFAULT_MAX_THREADS = 4
private const val DEFAULT_MIN_OPERATIONS = 2
private const val DEFAULT_MAX_OPERATIONS = 5

// in hybrid mode: testing progress threshold (in %) after which strategy switch
//   from Stress to ModelChecking strategy occurs
private const val STRATEGY_SWITCH_THRESHOLD = 0.25

private const val MINIMIZATION_INVOCATIONS_COUNT = 10_000
private const val MODEL_CHECKING_ON_ERROR_INVOCATIONS_COUNT = 10_000

