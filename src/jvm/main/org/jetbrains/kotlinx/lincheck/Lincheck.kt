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
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.forClasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.verify
import org.jetbrains.kotlinx.lincheck.transformation.withLincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import kotlin.reflect.jvm.javaMethod


object Lincheck {
    @JvmStatic
    fun <R> verifyWithModelChecker(
        invocations: Int = 1,
        block: () -> R
    ): LincheckFailure? {
        val scenario = ExecutionScenario(
            emptyList(),
            listOf(
                listOf(
                    Actor(
                        Wrapper<R>::run.javaMethod!!,
                        listOf(block)
                    )
                )
            ),
            emptyList(),
            null
        )

        val options = ModelCheckingOptions()
            .iterations(0)
            .invocationsPerIteration(invocations)
            .addCustomScenario(scenario)
            .addGuarantee(forClasses(Lincheck::class).allMethods().ignore())
            .addGuarantee(forClasses(Wrapper::class).allMethods().ignore())
            .verifier(ExecutionExceptionsVerifier::class.java)

        val testCfg = options.createTestConfigurations(Wrapper::class.java)

        withLincheckJavaAgent(testCfg.instrumentationMode) {
            val strategy = testCfg.createStrategy(Wrapper::class.java, scenario, null, null)
            val verifier = testCfg.createVerifier()

            for (i in 1..invocations) {
                if (!strategy.nextInvocation()) {
                    break
                }

                val result = strategy.runInvocation()
                val failure = strategy.verify(result, verifier)

                if (failure != null) {
                    return failure
                }
            }
        }

        return null
    }

    internal class Wrapper<R>() {
        fun run(block: () -> R) = block()
    }

    private class ExecutionExceptionsVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : Verifier {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            if (results == null) return true
            // User `check(...)`'s will fail and put their exceptions as an execution result, we must track that and disallow such outcomes.
            return results.parallelResults[0][0] !is ExceptionResult
        }
    }
}