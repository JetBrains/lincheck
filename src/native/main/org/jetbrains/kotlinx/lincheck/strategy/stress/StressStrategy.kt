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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*

actual class StressStrategy actual constructor(
    private val testCfg: StressCTestConfiguration,
    private val testClass: TestClass,
    scenario: ExecutionScenario,
    private val validationFunctions: List<ValidationFunction>,
    private val stateRepresentationFunction: StateRepresentationFunction?,
    private val verifier: Verifier
) : Strategy(scenario) {
    private var invocations = testCfg.invocationsPerIteration
    private var initThreadFunction = testCfg.initThreadFunction
    private var finishThreadFunction = testCfg.finishThreadFunction
    private var runner: Runner

    init {
        runner = ParallelThreadsRunner(
            strategy = this,
            testClass = testClass,
            validationFunctions = validationFunctions,
            stateRepresentationFunction = stateRepresentationFunction,
            timeoutMs = testCfg.timeoutMs,
            useClocks = UseClocks.RANDOM,
            initThreadFunction = initThreadFunction,
            finishThreadFunction = finishThreadFunction
        )
        try {
            runner.initialize()
        } catch (t: Throwable) {
            runner.close()
            throw t
        }
    }

    actual override fun run(): LincheckFailure? {
        // Run invocations
        try {
            for (invocation in 0 until invocations) {
                runner.also {
                    //println("invocation $invocation has started")
                    //val t1 = currentTimeMillis()
                    val ir = runner.run()
                    //val t2 = currentTimeMillis()
                    //println("invocation $invocation has invocated, took ${t2 - t1}ms to run")
                    when (ir) {
                        is CompletedInvocationResult -> {
                            if (!verifier.verifyResults(scenario, ir.results)) {
                                return IncorrectResultsFailure(scenario, ir.results)
                            } else {
                                ir.finalize()
                            }
                        }
                        else -> return ir.toLincheckFailure(scenario)
                    }
                    //val t3 = currentTimeMillis()
                    //println("invocation $invocation has verified, took ${t3 - t2}ms to verify")
                }
            }
        } finally {
            runner.close()
        }
        return null
    }
}