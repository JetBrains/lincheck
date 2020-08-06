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

import org.jetbrains.kotlinx.lincheck.consumeCPU
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*
import java.util.*

class StressStrategy(
    testCfg: StressCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentation: Method?,
    private val verifier: Verifier
) : Strategy(scenario) {
    private val random = Random(0)
    private val invocations = testCfg.invocationsPerIteration
    private val runner: Runner
    private val waits: Array<IntArray>?
    private val nextWaits: Array<IntIterator>?

    init {
        // Create waits if needed
        waits = if (testCfg.addWaits)
            Array(scenario.parallelExecution.size) {
                IntArray(scenario.parallelExecution[it].size)
            }
        else
            null
        nextWaits = waits?.map { it.iterator() }?.toTypedArray()
        // Create runner
        runner = ParallelThreadsRunner(this, testClass, validationFunctions, stateRepresentation, null, testCfg.timeoutMs)
    }

    override fun run(): LincheckFailure? {
        try {
            // Run invocations
            for (invocation in 0 until invocations) {
                initializeInvocation(invocation)
                when (val ir = runner.run()) {
                    is CompletedInvocationResult -> {
                        if (!verifier.verifyResults(scenario, ir.results))
                            return IncorrectResultsFailure(scenario, ir.results)
                    }
                    else -> return ir.toLincheckFailure(scenario)
                }
            }
            return null
        } finally {
            runner.close()
        }
    }

    override fun onActorStart(threadId: Int) {
        nextWaits?.let {
            val wait = it[threadId].nextInt()
            if (wait != 0)
                consumeCPU(wait)
        }
    }

    private fun initializeInvocation(invocation: Int) {
        // Specify waits if needed
        if (waits != null) {
            val maxWait = (invocation.toFloat() * MAX_WAIT / invocations).toInt() + 1
            for (waitsForThread in waits) {
                for (i in waitsForThread.indices) {
                    // no wait before the first actor, otherwise a random wait
                    waitsForThread[i] = if (i == 0) 0 else random.nextInt(maxWait)
                }
            }
            for (i in waits.indices)
                nextWaits!![i] = waits[i].iterator()
        }
    }
}

private const val MAX_WAIT = 100
