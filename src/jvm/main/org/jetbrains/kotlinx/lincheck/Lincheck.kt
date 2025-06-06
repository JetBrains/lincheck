/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import org.jetbrains.kotlinx.lincheck.strategy.runIteration
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.transformation.withLincheckDynamicJavaAgent
import org.jetbrains.kotlinx.lincheck.verifier.Verifier

object Lincheck {

    /**
     * This method will explore different interleavings of the [block] body and all the threads created within it,
     * searching for the first raised exception.
     *
     * @param invocations number of different interleavings of code in the [block] that should be explored.
     * @param block lambda which body will be a target for the interleavings exploration.
     */
    @JvmOverloads
    @JvmStatic
    fun runConcurrentTest(
        invocations: Int = DEFAULT_INVOCATIONS_COUNT,
        block: Runnable
    ) = runConcurrentTestInternal(LincheckSettings.default, invocations, block)

    /**
     * This method will explore different interleavings of the [block] body and all the threads created within it,
     * searching for the first raised exception.
     *
     * @param lincheckSettings settings that determine linchecks behaviour like analyse std library collections.
     * @param invocations number of different interleavings of code in the [block] that should be explored.
     * @param block lambda which body will be a target for the interleavings exploration.
     */
    @JvmOverloads
    @JvmStatic
    internal fun runConcurrentTestInternal(
        lincheckSettings: LincheckSettings,
        invocations: Int = DEFAULT_INVOCATIONS_COUNT,
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
            .analyzeStdLib(lincheckSettings.analyzeStdLib)
            .verifier(NoExceptionVerifier::class.java)

        val testCfg = options.createTestConfigurations(GeneralPurposeModelCheckingWrapper::class.java)
        withLincheckDynamicJavaAgent(testCfg.instrumentationMode) {
            LincheckJavaAgent.ensureObjectIsTransformed(block)
            val verifier = testCfg.createVerifier()
            val wrapperClass = GeneralPurposeModelCheckingWrapper::class.java
            testCfg.createStrategy(wrapperClass, scenario, null, null).use { strategy ->
                val failure = strategy.runIteration(invocations, verifier)
                if (failure != null) {
                    check(strategy is ModelCheckingStrategy)
                    if (ideaPluginEnabled) {
                        runPluginReplay(
                            testCfg = testCfg,
                            testClass = wrapperClass,
                            scenario = scenario,
                            validationFunction = null,
                            stateRepresentationMethod = null,
                            invocations = invocations,
                            verifier = verifier
                        )
                        throw LincheckAssertionError(failure)
                    }
                    throw LincheckAssertionError(failure)
                }
            }
        }
    }

    internal const val DEFAULT_INVOCATIONS_COUNT = 50_000
}

internal data class LincheckSettings(val analyzeStdLib: Boolean) {
    companion object {
        val default =  LincheckSettings(analyzeStdLib = true)
    }
}

internal class GeneralPurposeModelCheckingWrapper {
    fun runGPMCTest(block: Runnable) = block.run()
}

private val runGPMCTestMethod =
    GeneralPurposeModelCheckingWrapper::class.java.getDeclaredMethod("runGPMCTest", Runnable::class.java)

/**
 * [NoExceptionVerifier] checks that the lambda passed into [Lincheck.runConcurrentTestInternal] does not throw an exception.
 */
private class NoExceptionVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario, results: ExecutionResult): Boolean =
        results.parallelResults[0][0] !is ExceptionResult
}

