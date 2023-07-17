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
import java.lang.IllegalStateException
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
     * Add the specified custom [scenario] additionally to the generated ones.
     * If [invocations] count is specified, the scenario will be run exactly this number of times.
     */
    fun addCustomScenario(scenario: ExecutionScenario, invocations: Int? = null)

    /**
     * Runs the Lincheck test on the specified class.
     *
     * @return [LincheckFailure] if some bug has been found.
     */
    fun runTests(testClass: Class<*>, tracker: RunTracker? = null): LincheckFailure?
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
 * @return [LincheckFailure] if some bug has been found.
 */
fun LincheckOptions.checkImpl(testClass: Class<*>): LincheckFailure? =
    runTests(testClass)

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
fun LincheckOptions.addCustomScenario(invocations: Int? = null, scenarioBuilder: DSLScenarioBuilder.() -> Unit): Unit =
    addCustomScenario(scenario { scenarioBuilder() }, invocations)

fun LincheckOptions.check(testClass: KClass<*>) = check(testClass.java)

// For internal tests only.
enum class LincheckMode {
    Stress, ModelChecking, Hybrid
}

internal data class LincheckOptionsImpl(
    /* execution time options */
    var testingTimeMs: Long = DEFAULT_TESTING_TIME_MS,
    internal var invocationTimeoutMs: Long = CTestConfiguration.DEFAULT_TIMEOUT_MS,
    /* random scenarios generation options */
    internal var generateRandomScenarios: Boolean = true,
    override var maxThreads: Int = DEFAULT_MAX_THREADS,
    internal var minThreads: Int = DEFAULT_MIN_THREADS,
    override var maxOperationsInThread: Int = DEFAULT_MAX_OPERATIONS,
    internal var minOperationsInThread: Int = DEFAULT_MIN_OPERATIONS,
    internal var generateBeforeAndAfterParts: Boolean = true,
    /* custom scenarios options */
    internal val customScenariosOptions: MutableList<CustomScenarioOptions> = mutableListOf(),
    /* verification options */
    override var sequentialImplementation: Class<*>? = null,
    override var verifier: Class<out Verifier> = LinearizabilityVerifier::class.java,
    override var checkObstructionFreedom: Boolean = false,
    /* strategy options  */
    internal var mode: LincheckMode = LincheckMode.Hybrid,
    internal var minimizeFailedScenario: Boolean = true,
    internal var tryReproduceTrace: Boolean = false,
) : LincheckOptions {

    override var testingTimeInSeconds: Long
        get() = (testingTimeMs.toDouble() / 1000L).roundToLong()
        set(value) {
            testingTimeMs = value * 1000L
        }

    private val shouldRunCustomScenarios: Boolean
        get() = customScenariosOptions.size > 0

    private val shouldRunRandomScenarios: Boolean
        get() = generateRandomScenarios

    private val shouldRunStressStrategy: Boolean
        get() = (mode == LincheckMode.Stress) || (mode == LincheckMode.Hybrid)

    private val shouldRunModelCheckingStrategy: Boolean
        get() = (mode == LincheckMode.ModelChecking) || (mode == LincheckMode.Hybrid)

    private val stressTestingTimeMs: Long
        get() = when (mode) {
            LincheckMode.Hybrid -> round(testingTimeMs * STRATEGY_SWITCH_THRESHOLD).toLong()
            LincheckMode.Stress -> testingTimeMs
            LincheckMode.ModelChecking -> 0
        }

    private val modelCheckingTimeMs: Long
        get() = when (mode) {
            LincheckMode.Hybrid -> round(testingTimeMs * (1 - STRATEGY_SWITCH_THRESHOLD)).toLong()
            LincheckMode.ModelChecking -> testingTimeMs
            LincheckMode.Stress -> 0
        }

    override fun addCustomScenario(scenario: ExecutionScenario, invocations: Int?) {
        customScenariosOptions.add(
            CustomScenarioOptions(
                scenario = scenario,
                invocations = invocations ?: CUSTOM_SCENARIO_DEFAULT_INVOCATIONS_COUNT
            )
        )
    }

    override fun runTests(testClass: Class<*>, tracker: RunTracker?): LincheckFailure? {
        val testStructure = CTestStructure.getFromTestClass(testClass)
        if (customScenariosOptions.size > 0) {
            runCustomScenarios(testClass, testStructure, tracker)?.let { return it }
        }
        if (generateRandomScenarios) {
            runRandomScenarios(testClass, testStructure, tracker)?.let { return it }
        }
        return null
    }

    private fun runCustomScenarios(testClass: Class<*>, testStructure: CTestStructure, tracker: RunTracker?): LincheckFailure? {
        if (shouldRunStressStrategy) {
            val stressOptions = copy(
                mode = LincheckMode.Stress,
                generateRandomScenarios = false,
                tryReproduceTrace = (mode == LincheckMode.Hybrid)
            )
            stressOptions.runImpl(testClass, testStructure, tracker)?.let {
                return it
            }
        }
        if (shouldRunModelCheckingStrategy) {
            val modelCheckingOptions = copy(
                mode = LincheckMode.ModelChecking,
                generateRandomScenarios = false,
                tryReproduceTrace = true
            )
            modelCheckingOptions.runImpl(testClass, testStructure, tracker)?.let {
                return it
            }
        }
        return null
    }

    private fun runRandomScenarios(testClass: Class<*>, testStructure: CTestStructure, tracker: RunTracker?): LincheckFailure? {
        if (shouldRunStressStrategy) {
            val stressOptions = copy(
                mode = LincheckMode.Stress,
                testingTimeMs = stressTestingTimeMs,
                customScenariosOptions = mutableListOf(),
                tryReproduceTrace = (mode == LincheckMode.Hybrid)
            )
            stressOptions.runImpl(testClass, testStructure, tracker)?.let {
                return it
            }
        }
        if (shouldRunModelCheckingStrategy) {
            val modelCheckingOptions = copy(
                mode = LincheckMode.ModelChecking,
                testingTimeMs = modelCheckingTimeMs,
                customScenariosOptions = mutableListOf(),
                tryReproduceTrace = true
            )
            modelCheckingOptions.runImpl(testClass, testStructure, tracker)?.let {
                return it
            }
        }
        return null
    }

    private fun getRunName(testClass: Class<*>): String {
        check(shouldRunCustomScenarios xor shouldRunRandomScenarios)
        val scenariosKind = when {
            shouldRunCustomScenarios -> "CustomScenarios"
            shouldRunRandomScenarios -> "RandomScenarios"
            else -> throw IllegalStateException()
        }
        return "${testClass.name}-${scenariosKind}-${mode}-${abs(hashCode())}"
    }

    private fun runImpl(testClass: Class<*>, testStructure: CTestStructure, customTracker: RunTracker? = null): LincheckFailure? {
        check(mode in listOf(LincheckMode.Stress, LincheckMode.ModelChecking))
        check(shouldRunCustomScenarios xor shouldRunRandomScenarios)
        return customTracker.trackRun(getRunName(testClass), this) {
            val statisticsTracker = StatisticsTracker()
            val verifierManager = createVerifierManager(testClass)
            val planner = when {
                shouldRunCustomScenarios -> CustomScenariosPlanner(customScenariosOptions)
                shouldRunRandomScenarios -> RandomScenariosPlanner(mode, testStructure, statisticsTracker)
                else -> throw IllegalStateException()
            }
            val reporterManager = createReporterManager(statisticsTracker, planner)
            val tracker = trackersList(
                listOfNotNull(
                    statisticsTracker,
                    reporterManager,
                    customTracker,
                )
            )
            var failure = planner.runIterations(tracker) { scenario ->
                // TODO: move scenario validation to more appropriate place?
                scenario.validate()
                createStrategy(mode, testClass, testStructure, scenario) to verifierManager.verifier
            } ?: return@trackRun (null to statisticsTracker)
            // TODO: implement a minimization planner?
            if (minimizeFailedScenario) {
                reporterManager.reporter.logScenarioMinimization(failure.scenario)
                failure = failure.minimize {
                    it.run(
                        mode, testClass, testStructure,
                        createVerifier(testClass),
                        FixedInvocationsPlanner(MINIMIZATION_INVOCATIONS_COUNT)
                    )
                }
            }
            // TODO: move to StressStrategy.collectTrace() ?
            if (failure.trace == null && mode != LincheckMode.ModelChecking && tryReproduceTrace) {
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
            reporterManager.reporter.logFailedIteration(failure)
            return@trackRun (failure to statisticsTracker)
        }
    }

    private fun ExecutionScenario.run(
        currentMode: LincheckMode,
        testClass: Class<*>,
        testStructure: CTestStructure,
        verifier: Verifier,
        planner: InvocationsPlanner,
        statisticsTracker: StatisticsTracker? = null,
    ): LincheckFailure? =
        createStrategy(currentMode, testClass, testStructure, this).use {
            it.run(verifier, planner, statisticsTracker)
        }

    private fun createReporterManager(statistics: Statistics?, planner: Planner) = object : RunTracker {
        val reporter = Reporter(DEFAULT_LOG_LEVEL)

        override fun iterationStart(iteration: Int, scenario: ExecutionScenario) {
            reporter.logIteration(iteration + 1, scenario)
        }

        override fun iterationEnd(iteration: Int, failure: LincheckFailure?) {
            statistics?.apply {
                reporter.logIterationStatistics(
                    invocations = iterationsInvocationsCount[iteration],
                    runningTimeNano = iterationsRunningTimeNano[iteration],
                    remainingTimeNano = (planner.iterationsPlanner as? AdaptivePlanner)?.remainingTimeNano
                )
            }
        }
    }

    private fun RandomScenariosPlanner(mode: LincheckMode, testStructure: CTestStructure, statisticsTracker: StatisticsTracker): Planner =
        RandomScenariosAdaptivePlanner(
            mode = mode,
            minThreads = min(minThreads, maxThreads),
            minOperations = min(minOperationsInThread, maxOperationsInThread),
            maxThreads = maxThreads,
            maxOperations = maxOperationsInThread,
            generateBeforeAndAfterParts = generateBeforeAndAfterParts,
            scenarioGenerator = RandomExecutionGenerator(testStructure, testStructure.randomProvider),
            statisticsTracker = statisticsTracker,
            testingTimeMs = testingTimeMs,
        )

    private fun createVerifier(testClass: Class<*>) = verifier
        .getConstructor(Class::class.java)
        .newInstance(
            chooseSequentialSpecification(sequentialImplementation, testClass)
        )

    private fun createVerifierManager(testClass: Class<*>) = object : RunTracker {
        lateinit var verifier: Verifier
            private set

        init {
            refresh()
        }

        override fun iterationStart(iteration: Int, scenario: ExecutionScenario) {
            // For performance reasons, verifier re-uses LTS from previous iterations.
            // This behaviour is similar to a memory leak and can potentially cause OutOfMemoryError.
            // This is why we periodically create a new verifier to still have increased performance
            // from re-using LTS and limit the size of potential memory leak.
            // https://github.com/Kotlin/kotlinx-lincheck/issues/124
            if ((iteration + 1) % LinChecker.VERIFIER_REFRESH_CYCLE == 0) {
                refresh()
            }
        }

        private fun refresh() {
            verifier = createVerifier(testClass)
        }
    }

    private fun createStrategy(
        mode: LincheckMode,
        testClass: Class<*>,
        testStructure: CTestStructure,
        scenario: ExecutionScenario,
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

internal class CustomScenarioOptions(
    val scenario: ExecutionScenario,
    val invocations: Int,
)

private const val DEFAULT_TESTING_TIME_MS = 5000L
private const val DEFAULT_MIN_THREADS = 2
private const val DEFAULT_MAX_THREADS = 4
private const val DEFAULT_MIN_OPERATIONS = 2
private const val DEFAULT_MAX_OPERATIONS = 5

// in hybrid mode: testing progress threshold (in %) after which strategy switch
//   from Stress to ModelChecking strategy occurs
private const val STRATEGY_SWITCH_THRESHOLD = 0.25

private const val CUSTOM_SCENARIO_DEFAULT_INVOCATIONS_COUNT = 10_000
private const val MINIMIZATION_INVOCATIONS_COUNT = 10_000
private const val MODEL_CHECKING_ON_ERROR_INVOCATIONS_COUNT = 10_000

