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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.verifier.*

/**
 * Abstract class for test options.
 */
abstract class Options<OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> {
    internal var logLevel = DEFAULT_LOG_LEVEL
    protected var iterations = CTestConfiguration.DEFAULT_ITERATIONS
    protected var threads = CTestConfiguration.DEFAULT_THREADS
    protected var actorsPerThread = CTestConfiguration.DEFAULT_ACTORS_PER_THREAD
    protected var actorsBefore = CTestConfiguration.DEFAULT_ACTORS_BEFORE
    protected var actorsAfter = CTestConfiguration.DEFAULT_ACTORS_AFTER
    protected var executionGenerator = CTestConfiguration.DEFAULT_EXECUTION_GENERATOR
    protected var verifier = CTestConfiguration.DEFAULT_VERIFIER
    protected var minimizeFailedScenario = CTestConfiguration.DEFAULT_MINIMIZE_ERROR
    protected var sequentialSpecification: Class<*>? = null
    protected var timeoutMs: Long = CTestConfiguration.DEFAULT_TIMEOUT_MS
    protected var customScenarios: MutableList<ExecutionScenario> = mutableListOf()

    /**
     * Number of different test scenarios to be executed
     */
    fun iterations(iterations: Int): OPT = applyAndCast {
        this.iterations = iterations
    }

    /**
     * Use the specified number of threads for the parallel part of an execution.
     *
     * Note, that the the actual number of threads can be less due to some restrictions
     * like [Operation.runOnce].
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
     * like [Operation.runOnce].
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
     * like [Operation.runOnce].
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
     * like [Operation.runOnce].
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
     * Does nothing, states equivalence does not always improve performance of verification.
     *
     * Required correctness check of test instance state equivalency relation defined by the user.
     * It checked whether two new instances of a test class are equal.
     * If the check failed [[IllegalStateException]] was thrown.
     */
    @Deprecated("Does nothing, because equals/hashcode don't always improve performance of verification")
    fun requireStateEquivalenceImplCheck(require: Boolean): OPT = applyAndCast { }

    /**
     * If this feature is enabled and an invalid interleaving has been found,
     * *lincheck* tries to minimize the corresponding scenario in order to
     * construct a smaller one so that the test fails on it as well.
     * Enabled by default.
     */
    fun minimizeFailedScenario(minimizeFailedScenario: Boolean): OPT = applyAndCast {
        this.minimizeFailedScenario = minimizeFailedScenario
    }

    abstract fun createTestConfigurations(testClass: Class<*>): CTEST

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

    companion object {
        @Suppress("UNCHECKED_CAST")
        private inline fun <OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> Options<OPT, CTEST>.applyAndCast(
            block: Options<OPT, CTEST>.() -> Unit
        ) = this.apply {
            block()
        } as OPT
    }
}
