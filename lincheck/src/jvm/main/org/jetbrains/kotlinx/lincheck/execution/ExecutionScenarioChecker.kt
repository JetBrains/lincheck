/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.execution

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.datastructures.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy
import org.jetbrains.lincheck.datastructures.verifier.*
import org.jetbrains.lincheck.util.*

/**
 * Runs custom and generated scenarios, minimizes failures, and initiates plugin replay.
 */
internal class ExecutionScenarioChecker(
    private val testClass: Class<*>,
    private val settings: LincheckSettings,
) {
    private val testStructure = CTestStructure.getFromTestClass(testClass)

    private val reporter = Reporter(settings.logLevel)

    init {
        // Currently, we extract validation functions from the `testClass` structure,
        // so for custom scenarios declared with DSL, we have to set it up when `testClass` is scanned
        settings.scenarioGeneration.customScenarios.forEach { it.validationFunction = testStructure.validationFunction }
    }

    /**
     * Runs Lincheck to check the tested class under given configurations.
     *
     * @param cont Optional continuation taking [LincheckFailure] as an argument.
     *   The continuation is run in the context when Lincheck java-agent is still attached.
     */
    internal fun detectFailure(cont: ((LincheckFailure?) -> Unit)? = null): LincheckFailure? {
        lincheckVerificationStarted()
        var failure: LincheckFailure? = null
        withLincheckTestContext(settings.instrumentationMode) {
            failure = settings.checkScenarios()
            cont?.invoke(failure)
        }
        return failure
    }

    private fun LincheckSettings.checkScenarios(): LincheckFailure? {
        val generation = scenarioGeneration
        val iterations = generation.iterations
        var verifier = createVerifier(testClass)
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
        val scenarios = generation.customScenarios.asSequence() + randomScenarios.take(iterations)
        val scenariosSize = generation.customScenarios.size + iterations
        scenarios.forEachIndexed { i, scenario ->
            val isCustomScenario = (i < generation.customScenarios.size)
            // For performance reasons, verifier re-uses LTS from previous iterations.
            // This behavior is similar to a memory leak and can potentially cause OutOfMemoryError.
            // This is why we periodically create a new verifier to still have increased performance
            // from re-using LTS and limit the size of potential memory leak.
            // https://github.com/Kotlin/kotlinx-lincheck/issues/124
            if ((i + 1) % VERIFIER_REFRESH_CYCLE == 0)
                verifier = createVerifier(testClass)
            scenario.validate()
            reporter.logIteration(i + 1, scenariosSize, scenario)
            var failure = scenario.run(this, verifier)
            if (failure == null)
                return@forEachIndexed
            if (generation.minimizeFailedScenario && !isCustomScenario) {
                reporter.logScenarioMinimization(scenario)
                failure = failure.minimize { minimizedScenario ->
                    minimizedScenario.run(this, createVerifier(testClass))
                }
            }
            reporter.logFailedIteration(failure)
            runReplayForPlugin(failure, verifier)
            return failure
        }
        return null
    }

    /**
     * Enables replay mode and re-runs the failed scenario if Lincheck IDEA plugin is enabled.
     * We cannot initiate the failed interleaving replaying in the strategy code,
     * as the failing scenario might need to be minimized first.
     */
    private fun LincheckSettings.runReplayForPlugin(failure: LincheckFailure, verifier: Verifier) {
        if (ideaPluginEnabled && strategy is LincheckSettings.TestingStrategy.ModelChecking) {
            withIdeaPluginReplayModeEnabled {
                runPluginReplay(
                    failure = failure,
                    replayStrategy = createStrategy(failure.scenario) as ManagedStrategy,
                    invocations = scenarioGeneration.invocationsPerIteration,
                    verifier = verifier,
                )
            }
        }
    }

    private fun ExecutionScenario.run(
        settings: LincheckSettings,
        verifier: Verifier,
    ): LincheckFailure? {
        val strategy = settings.createStrategy(this)
        return strategy.use {
            it.runIteration(settings.scenarioGeneration.invocationsPerIteration, verifier)
        }
    }

    private fun LincheckSettings.createStrategy(scenario: ExecutionScenario) =
        createStrategy(
            testClass = testClass,
            scenario = scenario,
            validationFunction = testStructure.validationFunction,
        )

    private fun LincheckSettings.createExecutionGenerator(randomProvider: RandomProvider): ExecutionGenerator {
        if (scenarioGeneration.iterations > 0) {
            checkAtLeastOneMethodIsMarkedAsOperation()
        }
        legacyExecutionGeneratorFactory?.let { return it(testStructure, randomProvider) }
        val constructor = scenarioGeneration.executionGenerator.getConstructor(
            LincheckSettings::class.java,
            CTestStructure::class.java,
            RandomProvider::class.java
        )
        return constructor.newInstance(this, testStructure, randomProvider)
    }

    private fun checkAtLeastOneMethodIsMarkedAsOperation() {
        check(testStructure.hasOperations()) { NO_OPERATION_ERROR_MESSAGE }
    }
}

private const val VERIFIER_REFRESH_CYCLE = 100

internal const val NO_OPERATION_ERROR_MESSAGE =
    "You must specify at least one operation to test. Please refer to the user guide: https://kotlinlang.org/docs/introduction.html"
