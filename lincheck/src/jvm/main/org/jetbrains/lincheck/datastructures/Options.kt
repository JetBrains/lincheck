/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.datastructures

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.datastructures.verifier.*
import org.jetbrains.lincheck.util.DEFAULT_LOG_LEVEL
import org.jetbrains.lincheck.util.LoggingLevel
import kotlin.reflect.*

/**
 * Abstract class for test options.
 */
sealed class Options<OPT : Options<OPT>> {
    internal var logLevel = DEFAULT_LOG_LEVEL

    var iterations = DEFAULT_ITERATIONS
        protected set
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

    internal var ideaPluginReplayModeEnabled: Boolean = false
        private set

    internal fun enableIdeaPluginReplayMode() {
        ideaPluginReplayModeEnabled = true
    }

    internal fun disableIdeaPluginReplayMode() {
        ideaPluginReplayModeEnabled = false
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
        const val DEFAULT_ITERATIONS = 100
        const val DEFAULT_INVOCATIONS = 10_000

        const val DEFAULT_THREADS = 2
        const val DEFAULT_ACTORS_PER_THREAD = 5
        const val DEFAULT_ACTORS_BEFORE = 5
        const val DEFAULT_ACTORS_AFTER = 5

        const val DEFAULT_MINIMIZE_ERROR = true
        const val DEFAULT_TIMEOUT_MS: Long = 30_000 // 30 sec

        val DEFAULT_EXECUTION_GENERATOR: Class<out ExecutionGenerator?> = RandomExecutionGenerator::class.java
        val DEFAULT_VERIFIER: Class<out Verifier> = LinearizabilityVerifier::class.java

    }
}

internal fun Options<*>.createVerifier(testClass: Class<*>) =
    verifier.getConstructor(Class::class.java).newInstance(sequentialSpecification ?: testClass)

@Suppress("UNCHECKED_CAST")
private inline fun <OPT : Options<OPT>> Options<OPT>.applyAndCast(
    block: Options<OPT>.() -> Unit
) = this.apply {
    block()
} as OPT

internal val Options<*>.instrumentationMode: InstrumentationMode
    get() = when (this) {
        is ModelCheckingOptions ->
            if (useExperimentalModelChecking) InstrumentationMode.EXPERIMENTAL_MODEL_CHECKING
            else InstrumentationMode.MODEL_CHECKING
        is StressOptions -> InstrumentationMode.STRESS
    }

internal fun Options<*>.createStrategy(
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunction: Actor?,
): Strategy = when (this) {
    is ModelCheckingOptions -> createStrategy(testClass, scenario, validationFunction)
    is StressOptions -> createStrategy(testClass, scenario, validationFunction)
}

/**
 * Enables plugin replay for [block] and disables it when the block exits.
 */
internal inline fun <T> Options<*>.withIdeaPluginReplayModeEnabled(block: () -> T): T {
    enableIdeaPluginReplayMode()
    try {
        return block()
    } finally {
        disableIdeaPluginReplayMode()
    }
}
