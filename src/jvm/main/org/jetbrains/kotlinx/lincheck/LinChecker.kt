/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.transformation.withLincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import kotlin.reflect.*

/**
 * This class runs concurrent tests.
 */
class LinChecker(private val testClass: Class<*>, options: Options<*, *>?) {
    private val testStructure = CTestStructure.getFromTestClass(testClass)
    private val testConfigurations: List<CTestConfiguration>
    private val reporter: Reporter

    init {
        val logLevel = options?.logLevel ?: testClass.getAnnotation(LogLevel::class.java)?.value ?: DEFAULT_LOG_LEVEL
        reporter = Reporter(logLevel)
        testConfigurations = if (options != null) listOf(options.createTestConfigurations(testClass))
                             else createFromTestClassAnnotations(testClass)
        // Currently, we extract validation functions from testClass structure, so for custom scenarios declared
        // with DSL, we have to set up it when testClass is scanned
        testConfigurations.forEach { cTestConfiguration ->
            cTestConfiguration.customScenarios.forEach { it.validationFunction = testStructure.validationFunction }
        }
    }

    /**
     * @throws LincheckAssertionError if the testing data structure is incorrect.
     */
    fun check() {
        checkImpl { failure ->
            if (failure != null) throw LincheckAssertionError(failure)
        }
    }

    /**
     * Runs Lincheck to check the tested class under given configurations.
     *
     * @param continuation Optional continuation taking [LincheckFailure] as an argument.
     *   The continuation is run in the context when Lincheck java-agent is still attached.
     * @return [LincheckFailure] if a failure is discovered, null otherwise.
     */
    @Synchronized // never run Lincheck tests in parallel
    internal fun checkImpl(
        customTracker: LincheckRunTracker? = null,
        continuation: LincheckFailureContinuation? = null,
    ): LincheckFailure? {
        check(testConfigurations.isNotEmpty()) { "No Lincheck test configuration to run" }
        lincheckVerificationStarted()
        for (testCfg in testConfigurations) {
            withLincheckJavaAgent(testCfg.instrumentationMode) {
                val failure = testCfg.checkImpl(customTracker)
                if (failure != null) {
                    if (continuation != null) continuation(failure)
                    return failure
                }
            }
        }
        if (continuation != null) continuation(null)
        return null
    }

    private fun CTestConfiguration.checkImpl(customTracker: LincheckRunTracker? = null): LincheckFailure? {
        var verifier = createVerifier()
        val generator = createExecutionGenerator(testStructure.randomProvider)
        // create a sequence that generates scenarios lazily on demand
        val randomScenarios = generateSequence {
            generator.nextExecution().also {
                // reset the parameter generator ranges to start with the same initial bounds for each scenario.
                testStructure.parameterGenerators.forEach { it.reset() }
            }
        }
        // we want to handle custom scenarios and random scenarios uniformly by a single loop;
        // to achieve this, we join the custom scenarios list and
        // random scenarios generator into a single sequence;
        // this way the random scenarios are still generated lazily on demand
        // only after all custom scenarios are checked
        val scenarios = customScenarios.asSequence() + randomScenarios.take(iterations)
        val scenariosSize = customScenarios.size + iterations
        val tracker = ChainedRunTracker().apply {
            if (customTracker != null)
                addTracker(customTracker)
        }
        val statisticsTracker = tracker.addTrackerIfAbsent { LincheckStatisticsTracker() }
        scenarios.forEachIndexed { i, scenario ->
            val isCustomScenario = (i < customScenarios.size)
            // For performance reasons, verifier re-uses LTS from previous iterations.
            // This behavior is similar to a memory leak and can potentially cause OutOfMemoryError.
            // This is why we periodically create a new verifier to still have increased performance
            // from re-using LTS and limit the size of potential memory leak.
            // https://github.com/Kotlin/kotlinx-lincheck/issues/124
            if ((i + 1) % VERIFIER_REFRESH_CYCLE == 0)
                verifier = createVerifier()
            scenario.validate()
            reporter.logIteration(i, scenariosSize, scenario)
            var failure = scenario.run(i, this, verifier, tracker)
            reporter.logIterationStatistics(i, statisticsTracker)
            if (failure == null)
                return@forEachIndexed
            var j = i + 1
            if (minimizeFailedScenario && !isCustomScenario) {
                reporter.logScenarioMinimization(scenario)
                failure = failure.minimize { minimizedScenario ->
                    minimizedScenario.run(j++, this, createVerifier(), tracker)
                }
            }
            reporter.logFailedIteration(failure)
            runReplayForPlugin(j++, failure, verifier)
            return failure
        }
        return null
    }

    /**
     * Enables replay mode and re-runs the failed scenario if Lincheck IDEA plugin is enabled.
     * We cannot initiate the failed interleaving replaying in the strategy code,
     * as the failing scenario might need to be minimized first.
     */
    private fun CTestConfiguration.runReplayForPlugin(
        iteration: Int,
        failure: LincheckFailure,
        verifier: Verifier,
        tracker: LincheckRunTracker? = null,
    ) {
        if (ideaPluginEnabled() && this is ModelCheckingCTestConfiguration) {
            reporter.logFailedIteration(failure, loggingLevel = LoggingLevel.WARN)
            enableReplayModeForIdeaPlugin()
            val strategy = createStrategy(failure.scenario)
            check(strategy is ModelCheckingStrategy)
            val parameters = createIterationParameters(strategy)
            strategy.use {
                val replayedFailure = it.runIteration(iteration, parameters, verifier, tracker)
                check(replayedFailure != null)
                strategy.runReplayIfPluginEnabled(replayedFailure)
            }
        } else {
            reporter.logFailedIteration(failure)
        }
    }

    private fun ExecutionScenario.run(
        iteration: Int,
        testCfg: CTestConfiguration,
        verifier: Verifier,
        tracker: LincheckRunTracker? = null,
    ): LincheckFailure? {
        val strategy = testCfg.createStrategy(this)
        val parameters = testCfg.createIterationParameters(strategy)
        return strategy.use {
            it.runIteration(iteration, parameters, verifier, tracker)
        }
    }

    private fun Reporter.logIterationStatistics(iteration: Int, statisticsTracker: LincheckStatisticsTracker) {
        val statistics = statisticsTracker.iterationsStatistics[iteration]!!
        logIterationStatistics(statistics.totalInvocationsCount, statistics.totalRunningTimeNano)
    }

    private fun CTestConfiguration.createStrategy(scenario: ExecutionScenario) =
        createStrategy(
            testClass = testClass,
            scenario = scenario,
            validationFunction = testStructure.validationFunction,
            stateRepresentationMethod = testStructure.stateRepresentation,
        )

    private fun CTestConfiguration.createVerifier() =
        verifierClass.getConstructor(Class::class.java).newInstance(sequentialSpecification)

    private fun CTestConfiguration.createExecutionGenerator(randomProvider: RandomProvider): ExecutionGenerator {
        if (iterations > 0) {
            checkAtLeastOneMethodIsMarkedAsOperation(testClass)
        }
        val constructor = generatorClass.getConstructor(
            CTestConfiguration::class.java,
            CTestStructure::class.java,
            RandomProvider::class.java
        )
        return constructor.newInstance(this, testStructure, randomProvider)
    }

    private fun CTestConfiguration.createIterationParameters(strategy: Strategy) =
        IterationParameters(
            strategy = when (strategy) {
                is StressStrategy -> LincheckStrategy.Stress
                is ModelCheckingStrategy -> LincheckStrategy.ModelChecking
                else -> throw IllegalStateException("Unsupported Lincheck strategy")
            },
            invocationsBound = this.invocationsPerIteration,
            warmUpInvocationsCount = 0,
        )

    private val CTestConfiguration.invocationsPerIteration get() = when (this) {
        is ModelCheckingCTestConfiguration -> this.invocationsPerIteration
        is StressCTestConfiguration -> this.invocationsPerIteration
        else -> error("unexpected")
    }

    private fun checkAtLeastOneMethodIsMarkedAsOperation(testClass: Class<*>) {
        check (testClass.methods.any { it.isAnnotationPresent(Operation::class.java) }) { NO_OPERATION_ERROR_MESSAGE }
    }

    // This companion object is used for backwards compatibility.
    companion object {
        /**
         * Runs the specified concurrent tests. If [options] is null, the provided on
         * the testing class `@...CTest` annotations are used to specify the test parameters.
         *
         * @throws AssertionError if any of the tests fails.
         */
        @JvmOverloads
        @JvmStatic
        fun check(testClass: Class<*>, options: Options<*, *>? = null) {
            LinChecker(testClass, options).check()
        }

        private const val VERIFIER_REFRESH_CYCLE = 100
    }
}

// Tries to minimize the specified failing scenario to make the error easier to understand.
// The algorithm is greedy: it tries to remove one actor from the scenario and checks
// whether a test with the modified one fails with error as well. If it fails,
// then the scenario has been successfully minimized, and the algorithm tries to minimize it again, recursively.
// Otherwise, if no actor can be removed so that the generated test fails, the minimization is completed.
// Thus, the algorithm works in the linear time of the total number of actors.
private fun LincheckFailure.minimize(checkScenario: (ExecutionScenario) -> LincheckFailure?): LincheckFailure {
    var minimizedFailure = this
    while (true) {
        minimizedFailure = minimizedFailure.scenario.tryMinimize(checkScenario)
            ?: break
    }
    return minimizedFailure
}

private fun ExecutionScenario.tryMinimize(checkScenario: (ExecutionScenario) -> LincheckFailure?): LincheckFailure? {
    // Reversed indices to avoid conflicts with in-loop removals
    for (i in threads.indices.reversed()) {
        for (j in threads[i].indices.reversed()) {
            tryMinimize(i, j)
                ?.run(checkScenario)
                ?.let { return it }
        }
    }
    return null
}


/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass, options)
 * ```
 */
fun <O : Options<O, *>> O.check(testClass: Class<*>) = LinChecker.check(testClass, this)

/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass.java, options)
 * ```
 */
fun <O : Options<O, *>> O.check(testClass: KClass<*>) = this.check(testClass.java)

/**
 * Runs Lincheck to check the tested class under given configurations.
 *
 * @param testClass Tested class.
 * @return [LincheckFailure] if a failure is discovered, null otherwise.
 */
internal fun <O : Options<O, *>> O.checkImpl(testClass: Class<*>): LincheckFailure? =
    LinChecker(testClass, this).checkImpl()

/**
 * Runs Lincheck to check the tested class under given configurations.
 *
 * Takes the [LincheckFailureContinuation] as an argument.
 * This is required due to current limitations of our testing infrastructure.
 * Some tests need to inspect the internals of the failure object
 * (for example, the stack traces of exceptions thrown during the execution).
 * However, because Lincheck dynamically installs java-agent and then uninstalls it,
 * this process can invalidate some internal state of the failure object
 * (for example, the source code mapping information in the stack traces is typically lost).
 * To overcome this problem, we run the continuation in the context when Lincheck java-agent is still attached.
 *
 * @param testClass Tested class.
 * @param continuation Continuation taking [LincheckFailure] as an argument.
 *   The continuation is run in the context when Lincheck java-agent is still attached.
 * @return [LincheckFailure] if a failure is discovered, null otherwise.
 */
internal fun <O : Options<O, *>> O.checkImpl(testClass: Class<*>, continuation: LincheckFailureContinuation) {
    LinChecker(testClass, this).checkImpl(continuation = continuation)
}

internal typealias LincheckFailureContinuation = (LincheckFailure?) -> Unit

internal const val NO_OPERATION_ERROR_MESSAGE = "You must specify at least one operation to test. Please refer to the user guide: https://kotlinlang.org/docs/introduction.html"
