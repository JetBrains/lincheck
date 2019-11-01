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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.ManagedStrategyBase
import org.jetbrains.kotlinx.lincheck.verifier.Verifier

private const val startSwitchProbability = 0.05
private const val endSwitchProbability = 0.99

/**
 * RandomSwitchStrategy just switches at any codeLocation to a random thread
 */
internal class RandomSwitchStrategy(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        verifier: Verifier,
        testCfg: RandomSwitchCTestConfiguration,
        reporter: Reporter
) : ManagedStrategyBase(testClass, scenario, verifier, reporter, testCfg.hangingDetectionThreshold, testCfg.checkObstructionFreedom) {
   // maximum number of thread switches that managed strategy may use to search for incorrect execution
    private val maxInvocations = testCfg.maxInvocationsPerIteration
    private var switchProbability = startSwitchProbability

    @Throws(Exception::class)
    override fun runImpl(): TestReport {
        repeat(maxInvocations){
            // switch probability changes linearly from startSwitchProbability to endSwitchProbability
            switchProbability = startSwitchProbability + it * (endSwitchProbability - startSwitchProbability) / maxInvocations
            if (!checkResults(runInvocation())){
                report.errorInvocation = it + 1
                return report
            }
        }

        return TestReport(ErrorType.NO_ERROR)
    }

    override fun shouldSwitch(threadId: Int): Boolean {
        // TODO: can reduce number of random calls using geometric distribution
        return random.nextDouble() < switchProbability
    }

    override fun chooseThread(switchableThreads: Int): Int = random.nextInt(switchableThreads)

    override fun initializeInvocation(generateNewRandomSeed: Boolean) {
        super.initializeInvocation(generateNewRandomSeed)
        // start from random thread
        currentThread = random.nextInt(nThreads)
    }
}