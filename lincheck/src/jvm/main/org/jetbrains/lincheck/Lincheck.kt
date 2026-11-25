/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck

import org.jetbrains.lincheck.util.ExceptionResult
import org.jetbrains.lincheck.util.ideaPluginEnabled
import org.jetbrains.lincheck.scenario.ExecutionScenarioChecker
import kotlin.reflect.KClass
import org.jetbrains.lincheck.scenario.ExecutionResult
import org.jetbrains.lincheck.scenario.ExecutionScenario
import org.jetbrains.lincheck.scenario.parallelResults
import org.jetbrains.lincheck.runner.LambdaRunner
import org.jetbrains.lincheck.strategy.managed.ManagedStrategy
import org.jetbrains.lincheck.strategy.runIteration
import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation.ensureObjectIsTransformed
import org.jetbrains.lincheck.jvm.agent.withLincheckJavaAgent
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import sun.nio.ch.lincheck.Injections
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object Lincheck {

    /**
     * Explores interleavings of [block] and the threads it starts, failing on the first discovered exception.
     * Uses the model checking to explore interleavings.
     */
    @JvmStatic
    @JvmOverloads
    fun runConcurrentTest(invocations: Int = DEFAULT_INVOCATIONS, block: Runnable) =
        runConcurrentTest(invocations, LincheckSettings { modelChecking() }, block)

    /**
     * Explores interleavings of [block] and its threads under [memoryModel],
     * failing on the first discovered exception.
     *
     * @param memoryModel the memory model the exploration runs under. A model weaker than
     *   [MemoryModel.SequentialConsistency] selects the event-structure algorithm on its own,
     *   as it is the only one exploring relaxed executions.
     * @throws LincheckAssertionError if an invocation fails.
     */
    @JvmStatic
    fun runConcurrentTest(
        invocations: Int = DEFAULT_INVOCATIONS,
        memoryModel: MemoryModel,
        block: Runnable,
    ) = runConcurrentTest(invocations, LincheckSettings {
        modelChecking { this.memoryModel = memoryModel }
    }, block)

    /**
     * Runs [block] with the default invocation limit with given [settings].
     *
     * When [LincheckSettings.TestingStrategy.Stress] strategy is used,
     * runs [block] repeatedly under the JVM scheduler;
     * when [LincheckSettings.TestingStrategy.ModelChecking] is used,
     * explores interleavings of [block] and the threads it starts.
     *
     * @throws IllegalArgumentException if the settings are incompatible with lambda testing.
     * @throws LincheckAssertionError if an invocation fails.
     */
    @JvmStatic
    fun runConcurrentTest(settings: LincheckSettings, block: Runnable) =
        runConcurrentTest(DEFAULT_INVOCATIONS, settings, block)

    /**
     * Runs [block] up to [invocations] times with given [settings].
     *
     * When [LincheckSettings.TestingStrategy.Stress] strategy is used,
     * runs [block] repeatedly under the JVM scheduler;
     * when [LincheckSettings.TestingStrategy.ModelChecking] is used,
     * explores interleavings of [block] and the threads it starts.
     *
     * @throws IllegalArgumentException if [invocations] is not positive or the settings are incompatible.
     * @throws LincheckAssertionError if an invocation fails.
     */
    @JvmStatic
    fun runConcurrentTest(invocations: Int, settings: LincheckSettings, block: Runnable) {
        require(invocations > 0) { "invocations must be positive" }
        val configuration = settings.copy(forLambda = true)
        withLincheckTestContext(configuration.instrumentationMode) {
            ensureObjectIsTransformed(block)
            val verifier = NoExceptionVerifier()
            configuration.createStrategy(LambdaRunner(configuration.effectiveInvocationTimeoutMs, block)).use { strategy ->
                val failure = strategy.runIteration(invocations, verifier)
                if (failure != null) {
                    // Only managed strategies can replay a failing interleaving in the IDE.
                    if (ideaPluginEnabled && strategy is ManagedStrategy) {
                        configuration.withIdeaPluginReplayModeEnabled {
                            runPluginReplay(
                                failure = failure,
                                replayStrategy = configuration.createStrategy(
                                    LambdaRunner(configuration.effectiveInvocationTimeoutMs, block)
                                ) as ManagedStrategy,
                                invocations = invocations,
                                verifier = verifier,
                            )
                        }
                    }
                    throw LincheckAssertionError(failure)
                }
            }
        }
    }

    /**
     * Checks the annotated operations of [testClass] using the stress strategy by default.
     */
    @JvmStatic
    fun runConcurrentTest(testClass: Class<*>) = runConcurrentTest(LincheckSettings(), testClass)

    /**
     * Checks the annotated operations of the Kotlin [testClass] using default settings.
     */
    @JvmStatic
    fun runConcurrentTest(testClass: KClass<*>) = runConcurrentTest(LincheckSettings(), testClass)

    /**
     * Checks the annotated operations of [testClass] on generated and custom scenarios using a copy of [settings].
     *
     * @throws LincheckAssertionError if a scenario fails verification.
     */
    @JvmStatic
    fun runConcurrentTest(settings: LincheckSettings, testClass: Class<*>) {
        ExecutionScenarioChecker(testClass, settings.copy(forLambda = false)).detectFailure { failure ->
            if (failure != null) throw LincheckAssertionError(failure)
        }
    }

    /**
     * Checks the annotated operations of the Kotlin [testClass] using a copy of [settings].
     */
    @JvmStatic
    fun runConcurrentTest(settings: LincheckSettings, testClass: KClass<*>) =
        runConcurrentTest(settings, testClass.java)

    internal val DEFAULT_INVOCATIONS: Int = Integer.getInteger("lincheck.defaultInvocations", 10_000)
}

/**
 * [NoExceptionVerifier] checks that the lambda passed into [Lincheck.runConcurrentTest] does not throw an exception.
 */
internal class NoExceptionVerifier : Verifier {
    override fun verifyResults(scenario: ExecutionScenario, results: ExecutionResult): Boolean =
        results.parallelResults[0][0] !is ExceptionResult
}

/**
 * Executes the provided [block] of code within the context of Lincheck test. This includes:
 * - locking the testing process using a shared lock to ensure thread-safety of
 *   bytecode instrumentation and Lincheck analysis
 *   (therefore, as for now, Lincheck tests cannot be run in parallel);
 * - attaching java-agent for bytecode instrumentation.
 *
 * @param instrumentationMode the mode of Lincheck instrumentation to be applied.
 * @param block the code block to execute within the Lincheck test context
 */
internal inline fun withLincheckTestContext(
    instrumentationMode: InstrumentationMode,
    block: () -> Unit
) {
    LINCHECK_TEST_LOCK.withLock {
        withLincheckJavaAgent(instrumentationMode) {
            Injections.enableThreadLocalEventTracking()
            try {
                block()
            } finally {
                Injections.disableThreadLocalEventTracking()
            }
        }
    }
}

// This lock is used to ensure Lincheck tests
// are never run in parallel, thus, avoiding
// parallel javaagent install/uninstall calls.
private val LINCHECK_TEST_LOCK = ReentrantLock()
