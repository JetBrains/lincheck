/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.strategy.stress

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.CompletedInvocationResult
import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner
import org.jetbrains.kotlinx.lincheck.runner.UseClocks
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.strategy.toLincheckFailure
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method

class StressStrategy(
    testCfg: StressCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?,
    private val verifier: Verifier
) : Strategy(scenario) {
    private val invocations = testCfg.invocationsPerIteration
    private val runner = ParallelThreadsRunner(
        strategy = this,
        testClass = testClass,
        validationFunctions = validationFunctions,
        stateRepresentationFunction = stateRepresentationFunction,
        timeoutMs = testCfg.timeoutMs,
        useClocks = UseClocks.RANDOM
    )

    override fun run(): LincheckFailure? {
        runner.use {
            // Run invocations
            for (invocation in 0 until invocations) {
                val invocationResult = runner.run()
                when (invocationResult) {
                    is CompletedInvocationResult -> {
                        if (!verifier.verifyResults(scenario, invocationResult.results))
                            return IncorrectResultsFailure(scenario, invocationResult.results)
                    }
                    else -> return invocationResult.toLincheckFailure(scenario)
                }
            }
            return null
        }
    }
}