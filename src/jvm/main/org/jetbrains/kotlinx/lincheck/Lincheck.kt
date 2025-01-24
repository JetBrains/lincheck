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

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.ensureObjectIsTransformed
import org.jetbrains.kotlinx.lincheck.transformation.withLincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import org.jetbrains.kotlinx.lincheck.strategy.verify


@RequiresOptIn(message = "The model checking API is experimental and could change in the future.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class ExperimentalModelCheckingAPI

/**
 * This method will explore different interleavings of the [block] body and all the threads created within it,
 * searching for the first raised exception.
 *
 * @param invocations number of different interleavings of code in the [block] that should be explored.
 * @param block lambda which body will be a target for the interleavings exploration.
 */
@ExperimentalModelCheckingAPI
fun <R> runConcurrentTest(
    invocations: Int = DEFAULT_INVOCATIONS_COUNT,
    block: () -> R
) {
    val scenario = scenario {
        parallel {
            thread { actor(GeneralPurposeModelCheckingWrapper<R>::run, block) }
        }
    }

    val options = ModelCheckingOptions()
        .iterations(0)
        .addCustomScenario(scenario)
        .invocationsPerIteration(invocations)
        .verifier(NoExceptionVerifier::class.java)

    val testCfg = options.createTestConfigurations(GeneralPurposeModelCheckingWrapper::class.java)

    withLincheckJavaAgent(testCfg.instrumentationMode) {
        ensureObjectIsTransformed(block)
        val strategy = testCfg.createStrategy(GeneralPurposeModelCheckingWrapper::class.java, scenario, null, null)
        val verifier = testCfg.createVerifier()

        for (i in 1..invocations) {
            if (!strategy.nextInvocation()) {
                break
            }
            val result = strategy.runInvocation()
            val failure = strategy.verify(result, verifier)
            if (failure != null) {
                throw LincheckAssertionError(failure)
            }
        }
    }
}

internal class GeneralPurposeModelCheckingWrapper<R>() {
    fun run(block: () -> R) = block()
}

/**
 * [NoExceptionVerifier] checks that the lambda passed into [runConcurrentTest] does not throw an exception.
 */
internal class NoExceptionVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario, results: ExecutionResult): Boolean =
        results.parallelResults[0][0] !is ExceptionResult
}

private const val DEFAULT_INVOCATIONS_COUNT = 50_000
