/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.strategy.randomswitch

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.CompletedInvocationResult
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.ManagedStrategyBase
import org.jetbrains.kotlinx.lincheck.strategy.modelchecking.ModelCheckingCTestConfiguration
import org.jetbrains.kotlinx.lincheck.strategy.toLincheckFailure
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method

private const val startSwitchProbability = 0.05
private const val endSwitchProbability = 0.99

/**
 * RandomSwitchStrategy just switches at every code location to a random thread.
 * For internal experiments only!
 */
internal class RandomSwitchStrategy(
        testCfg: RandomSwitchCTestConfiguration,
        testClass: Class<*>,
        scenario: ExecutionScenario,
        validationFunctions: List<Method>,
        verifier: Verifier
) : ManagedStrategyBase(
        testClass, scenario, verifier, validationFunctions, ModelCheckingCTestConfiguration.DEFAULT_HANGING_DETECTION_THRESHOLD,
        false, ArrayList(ModelCheckingCTestConfiguration.DEFAULT_GUARANTEES)
) {
    // the number of invocations that the managed strategy may use to search for an incorrect execution
    private val maxInvocations = testCfg.invocationsPerIteration
    private var switchProbability = startSwitchProbability

    @Throws(Exception::class)
    override fun runImpl(): LincheckFailure? {
        repeat(maxInvocations){
            // switch probability changes linearly from startSwitchProbability to endSwitchProbability
            switchProbability = startSwitchProbability + it * (endSwitchProbability - startSwitchProbability) / maxInvocations
            when (val ir = runInvocation()) {
                is CompletedInvocationResult -> {
                    if (!verifier.verifyResults(scenario, ir.results))
                    return IncorrectResultsFailure(scenario, ir.results)
                }
                else -> return ir.toLincheckFailure(scenario)
            }
        }
        return null
    }

    override fun shouldSwitch(threadId: Int): Boolean {
        // TODO: can reduce the number of random calls using geometric distribution
        return random.nextDouble() < switchProbability
    }

    override fun chooseThread(switchableThreads: Int): Int = random.nextInt(switchableThreads)

    override fun initializeInvocation() {
        super.initializeInvocation()
        // start from random thread
        currentThread = random.nextInt(nThreads)
    }
}