/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.stress

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*

class StressStrategy(
    testCfg: StressCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?,
    private val verifier: Verifier
) : Strategy(scenario) {
    private val invocations = testCfg.invocationsPerIteration
    private val runner: Runner

    init {
        runner = ParallelThreadsRunner(
            strategy = this,
            testClass = testClass,
            validationFunctions = validationFunctions,
            stateRepresentationFunction = stateRepresentationFunction,
            timeoutMs = testCfg.timeoutMs,
            useClocks = UseClocks.RANDOM
        )
        try {
            runner.initialize()
        } catch (t: Throwable) {
            runner.close()
            throw t
        }
    }

    override fun run(): LincheckFailure? {
        runner.use {
            // Run invocations
            for (invocation in 0 until invocations) {
                when (val ir = runner.run()) {
                    is CompletedInvocationResult -> {
                        if (!verifier.verifyResults(scenario, ir.results))
                            return IncorrectResultsFailure(scenario, ir.results)
                    }
                    else -> return ir.toLincheckFailure(scenario)
                }
            }
            return null
        }
    }
}