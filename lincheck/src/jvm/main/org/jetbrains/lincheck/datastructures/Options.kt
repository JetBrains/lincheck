/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:Suppress("DEPRECATION")

package org.jetbrains.lincheck.datastructures

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.lincheck.datastructures.verifier.*
import org.jetbrains.lincheck.util.DEFAULT_LOG_LEVEL
import org.jetbrains.lincheck.util.LoggingLevel
import kotlin.reflect.*

/**
 * Abstract class for test options.
 */
@Deprecated("Use LincheckSettings and Lincheck.runConcurrentTest instead.", level = DeprecationLevel.WARNING)
sealed class Options<OPT : Options<OPT>> {
    internal var logLevel = DEFAULT_LOG_LEVEL

    /**
     * Number of scenarios, defaulting to `lincheck.defaultRandomScenarios` when set.
     */
    var iterations = DEFAULT_ITERATIONS
        protected set
    /**
     * Invocations per scenario, defaulting to `lincheck.defaultInvocationsPerScenario` when set.
     */
    var invocationsPerIteration = DEFAULT_INVOCATIONS
        protected set

    var threads = DEFAULT_THREADS
        protected set
    var actorsPerThread = DEFAULT_ACTORS_PER_THREAD
        protected set
    var actorsBefore = DEFAULT_ACTORS_BEFORE
        protected set
    var actorsAfter = DEFAULT_ACTORS_AFTER
        protected set

    var executionGenerator = DEFAULT_EXECUTION_GENERATOR
        protected set

    var timeoutMs: Long = DEFAULT_TIMEOUT_MS
        protected set

    var verifier = DEFAULT_VERIFIER
        protected set
    var sequentialSpecification: Class<*>? = null
        protected set

    var minimizeFailedScenario = DEFAULT_MINIMIZE_ERROR
        protected set

    var customScenarios: MutableList<ExecutionScenario> = mutableListOf()
        protected set

    /**
     * Number of different test scenarios to be executed
     */
    fun iterations(iterations: Int): OPT = applyAndCast {
        this.iterations = iterations
    }

    /**
     * Number of times each test scenario to be executed.
     * Lincheck can use fewer invocations if it requires fewer ones to study all possible interleavings.
     */
    fun invocationsPerIteration(invocations: Int): OPT = applyAndCast {
        this.invocationsPerIteration = invocations
    }

    /**
     * Use the specified number of threads for the parallel part of an execution.
     *
     * Note, that the the actual number of threads can be less due to some restrictions
     * like [org.jetbrains.kotlinx.lincheck.annotations.Operation.runOnce].
     *
     * @see ExecutionScenario.parallelExecution
     */
    fun threads(threads: Int): OPT = applyAndCast {
        this.threads = threads
    }

    /**
     * Generate the specified number of operations for each thread of the parallel part of an execution.
     *
     * Note, that the the actual number of operations can be less due to some restrictions
     * like [org.jetbrains.kotlinx.lincheck.annotations.Operation.runOnce].
     *
     * @see ExecutionScenario.parallelExecution
     */
    fun actorsPerThread(actorsPerThread: Int): OPT = applyAndCast {
        this.actorsPerThread = actorsPerThread
    }

    /**
     * Generate the specified number of operation for the initial sequential part of an execution.
     *
     * Note, that the the actual number of operations can be less due to some restrictions
     * like [org.jetbrains.kotlinx.lincheck.annotations.Operation.runOnce].
     *
     * @see ExecutionScenario.initExecution
     */
    fun actorsBefore(actorsBefore: Int): OPT = applyAndCast {
        this.actorsBefore = actorsBefore
    }

    /**
     * Generate the specified number of operation for the last sequential part of an execution.
     *
     * Note, that the the actual number of operations can be less due to some restrictions
     * like [org.jetbrains.kotlinx.lincheck.annotations.Operation.runOnce].
     *
     * @see ExecutionScenario.postExecution
     */
    fun actorsAfter(actorsAfter: Int): OPT = applyAndCast {
        this.actorsAfter = actorsAfter
    }

    /**
     * Use the specified execution generator.
     */
    fun executionGenerator(executionGenerator: Class<out ExecutionGenerator?>): OPT = applyAndCast {
        this.executionGenerator = executionGenerator
    }

    /**
     * Use the specified verifier.
     */
    fun verifier(verifier: Class<out Verifier?>): OPT = applyAndCast {
        this.verifier = verifier
    }

    /**
     * If this feature is enabled and an invalid interleaving has been found,
     * *lincheck* tries to minimize the corresponding scenario in order to
     * construct a smaller one so that the test fails on it as well.
     * Enabled by default.
     */
    fun minimizeFailedScenario(minimizeFailedScenario: Boolean): OPT = applyAndCast {
        this.minimizeFailedScenario = minimizeFailedScenario
    }

    /**
     * Set logging level, [DEFAULT_LOG_LEVEL] is used by default.
     */
    fun logLevel(logLevel: LoggingLevel): OPT = applyAndCast {
        this.logLevel = logLevel
    }

    /**
     * The specified class defines the sequential behavior of the testing data structure;
     * it is used by [Verifier] to build a labeled transition system,
     * and should have the same methods as the testing data structure.
     *
     * By default, the provided concurrent implementation is used in a sequential way.
     */
    fun sequentialSpecification(clazz: Class<*>?): OPT = applyAndCast {
        sequentialSpecification = clazz
    }

    /**
     * Examine the specified custom scenario additionally to the generated ones.
     */
    fun addCustomScenario(scenario: ExecutionScenario) = applyAndCast {
        customScenarios.add(scenario)
    }

    /**
     * Examine the specified custom scenario additionally to the generated ones.
     */
    fun addCustomScenario(scenarioBuilder: DSLScenarioBuilder.() -> Unit) =
        addCustomScenario(scenario { scenarioBuilder() })

    /**
     * Internal, DO NOT USE.
     */
    internal fun invocationTimeout(timeoutMs: Long): OPT = applyAndCast {
        this.timeoutMs = timeoutMs
    }

    /**
     * Runs the specified concurrent test with this options.
     */
    fun check(testClass: Class<*>) {
        detectFailure(testClass) { failure ->
            if (failure != null) throw LincheckAssertionError(failure)
        }
    }

    /**
     * Runs the specified concurrent test with this options.
     */
    fun check(testClass: KClass<*>) = check(testClass.java)

    companion object {
        @JvmField
        val DEFAULT_ITERATIONS: Int = LincheckSettings.ScenarioGeneration.DEFAULT_RANDOM_SCENARIOS
        @JvmField
        val DEFAULT_INVOCATIONS: Int = LincheckSettings.ScenarioGeneration.DEFAULT_INVOCATIONS_PER_SCENARIO

        const val DEFAULT_THREADS = LincheckSettings.ScenarioGeneration.DEFAULT_THREADS
        const val DEFAULT_ACTORS_PER_THREAD = LincheckSettings.ScenarioGeneration.DEFAULT_OPERATIONS_PER_THREAD
        const val DEFAULT_ACTORS_BEFORE = LincheckSettings.ScenarioGeneration.DEFAULT_OPERATIONS_BEFORE
        const val DEFAULT_ACTORS_AFTER = LincheckSettings.ScenarioGeneration.DEFAULT_OPERATIONS_AFTER

        const val DEFAULT_MINIMIZE_ERROR = LincheckSettings.ScenarioGeneration.DEFAULT_MINIMIZE_ERROR
        const val DEFAULT_TIMEOUT_MS: Long = LincheckSettings.DEFAULT_INVOCATION_TIMEOUT_MS

        val DEFAULT_EXECUTION_GENERATOR: Class<out ExecutionGenerator?> = LincheckSettings.ScenarioGeneration.DEFAULT_EXECUTION_GENERATOR
        val DEFAULT_VERIFIER: Class<out Verifier> = LincheckSettings.ScenarioGeneration.DEFAULT_VERIFIER

    }
}

@Suppress("UNCHECKED_CAST")
private inline fun <OPT : Options<OPT>> Options<OPT>.applyAndCast(
    block: Options<OPT>.() -> Unit
) = this.apply {
    block()
} as OPT

// Compatibility bridge for legacy tests and clients; execution consumes only LincheckSettings.
internal fun Options<*>.createStrategy(
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunction: Actor?,
): Strategy = toSettings().createStrategy(testClass, scenario, validationFunction)

@JvmName("toSettings")
internal fun Options<*>.toSettings(): LincheckSettings = LincheckSettings().also { settings ->

    settings.invocationTimeoutMs = timeoutMs
    settings.logLevel = logLevel
    settings.scenarioGeneration.also {
        it.invocationsPerScenario = invocationsPerIteration
        it.randomScenarios = iterations
        it.threads = threads
        it.operationsPerThread = actorsPerThread
        it.operationsBefore = actorsBefore
        it.operationsAfter = actorsAfter
        it.executionGenerator = executionGenerator
    }

    settings.scenarioGeneration.verifier = verifier
    settings.scenarioGeneration.sequentialSpecification = sequentialSpecification
    settings.scenarioGeneration.minimizeFailedScenario = minimizeFailedScenario
    customScenarios.forEach(settings.scenarioGeneration::addCustomScenario)
    settings.strategy = when (this) {
        is StressOptions -> stressSettings()
        is ModelCheckingOptions -> modelCheckingSettings()
    }

    // Older custom generators declare an Options constructor.
    // Keep reflection at the legacy boundary.
    val legacyConstructor = runCatching {
        executionGenerator.getConstructor(Options::class.java, CTestStructure::class.java, RandomProvider::class.java)
    }.getOrNull()
    val hasSettingsConstructor = runCatching {
        executionGenerator.getConstructor(LincheckSettings::class.java, CTestStructure::class.java, RandomProvider::class.java)
    }.isSuccess
    if (!hasSettingsConstructor && legacyConstructor != null) {
        settings.legacyExecutionGeneratorFactory = { structure, random ->
            legacyConstructor.newInstance(this, structure, random)
        }
    }
}
