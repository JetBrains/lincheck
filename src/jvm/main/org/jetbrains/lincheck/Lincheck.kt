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

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.ExceptionResult
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.createVerifier
import org.jetbrains.kotlinx.lincheck.runPluginReplay
import org.jetbrains.kotlinx.lincheck.ideaPluginEnabled
import org.jetbrains.kotlinx.lincheck.CTestConfiguration
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import org.jetbrains.kotlinx.lincheck.strategy.runIteration
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.ensureObjectIsTransformed
import org.jetbrains.kotlinx.lincheck.transformation.withLincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import kotlin.reflect.KClass

object Lincheck {

    /**
     * This method will explore different interleavings of the [block] body and all the threads created within it,
     * searching for the first raised exception.
     *
     * @param invocations number of different interleavings of code in the [block] that should be explored.
     * @param block lambda which body will be a target for the interleavings exploration.
     *
     * @throws LincheckAssertionError if the tets block finished with error in one of the interleavings.
     */
    @JvmStatic
    @JvmOverloads
    fun runConcurrentTest(
        invocations: Int = DEFAULT_INVOCATIONS,
        block: Runnable
    ) = runConcurrentTestInternal(invocations, LincheckSettings.DEFAULT, block)

    /**
     * This method will explore different interleavings of the [block] body and all the threads created within it,
     * searching for the first raised exception.
     *
     * @param invocations number of different interleavings of code in the [block] that should be explored.
     * @param settings advanced settings that determine Lincheck behavior.
     * @param block lambda which body will be a target for the interleavings exploration.
     *
     * @throws LincheckAssertionError in case when some exception was discovered.
     */
    @JvmStatic
    internal fun runConcurrentTestInternal(
        invocations: Int = DEFAULT_INVOCATIONS,
        settings: LincheckSettings,
        block: Runnable
    ) {
        val scenario = ExecutionScenario(
            initExecution = emptyList(),
            parallelExecution = listOf(
                listOf( // Main thread
                    Actor(method = runGPMCTestMethod, arguments = listOf(block))
                )
            ),
            postExecution = emptyList(),
            validationFunction = null
        )

        val options = ModelCheckingOptions()
            .iterations(0)
            .addCustomScenario(scenario)
            .invocationsPerIteration(invocations)
            .analyzeStdLib(settings.analyzeStdLib)
            .verifier(NoExceptionVerifier::class.java)

        val testCfg = options.createTestConfigurations(GeneralPurposeModelCheckingWrapper::class.java)
        withLincheckJavaAgent(testCfg.instrumentationMode) {
            ensureObjectIsTransformed(block)
            val verifier = testCfg.createVerifier()
            val wrapperClass = GeneralPurposeModelCheckingWrapper::class.java
            testCfg.createStrategy(wrapperClass, scenario, null, null).use { strategy ->
                val failure = strategy.runIteration(invocations, verifier)
                if (failure != null) {
                    check(strategy is ModelCheckingStrategy)
                    if (ideaPluginEnabled) {
                        runPluginReplay(
                            settings = testCfg.createSettings(),
                            testClass = wrapperClass,
                            scenario = scenario,
                            validationFunction = null,
                            stateRepresentationMethod = null,
                            invocations = invocations,
                            verifier = verifier
                        )
                    }
                    throw LincheckAssertionError(failure)
                }
            }
        }
    }

    internal const val DEFAULT_INVOCATIONS = CTestConfiguration.DEFAULT_INVOCATIONS
}

internal class GeneralPurposeModelCheckingWrapper {
    fun runGPMCTest(block: Runnable) = block.run()
}

private val runGPMCTestMethod =
    GeneralPurposeModelCheckingWrapper::class.java.getDeclaredMethod("runGPMCTest", Runnable::class.java)

/**
 * [NoExceptionVerifier] checks that the lambda passed into [Lincheck.runConcurrentTestInternal] does not throw an exception.
 */
internal class NoExceptionVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario, results: ExecutionResult): Boolean =
        results.parallelResults[0][0] !is ExceptionResult
}

