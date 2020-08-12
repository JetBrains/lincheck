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
package org.jetbrains.kotlinx.lincheck.strategy.managed.randomswitch

import com.sun.org.apache.bcel.internal.generic.SWITCH
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*
import kotlin.random.*

/**
 * RandomSwitchStrategy just switches at every code location to a random thread.
 * For internal experiments only!
 */
internal class RandomSwitchStrategy(
    testCfg: RandomSwitchCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentation: Method?,
    verifier: Verifier
) : ManagedStrategyBase(
        testClass, scenario, verifier, validationFunctions, stateRepresentation, testCfg
) {
    // the number of invocations that the managed strategy may use to search for an incorrect execution
    private val invocations = testCfg.invocationsPerIteration
    private var switchProbability = MIN_SWITCH_PROBABILITY
    private var executionRandomSeed: Long = 0
    private lateinit var executionRandom: Random

    @Throws(Exception::class)
    override fun runImpl(): LincheckFailure? {
        repeat(invocations){
            // The switch probability increases linearly
            switchProbability = MIN_SWITCH_PROBABILITY + it * (MAX_SWITCH_PROBABILITY - MIN_SWITCH_PROBABILITY) / invocations
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

    override fun shouldSwitch(threadId: Int): StrategyEvent {
        // TODO: the number of random calls can be reduced via the geometric distribution
        return if (executionRandom.nextDouble() < switchProbability) StrategyEvent.SWITCH else StrategyEvent.NONE
    }

    override fun chooseThread(switchableThreads: Int): Int = executionRandom.nextInt(switchableThreads)

    override fun initializeInvocation(repeatExecution: Boolean) {
        if (!repeatExecution)
            executionRandomSeed = generationRandom.nextLong()
        executionRandom = Random(executionRandomSeed)
        // start from a random thread.
        currentThread = executionRandom.nextInt(nThreads)
        super.initializeInvocation(repeatExecution)
    }
}

private const val MIN_SWITCH_PROBABILITY = 0.05
private const val MAX_SWITCH_PROBABILITY = 0.99