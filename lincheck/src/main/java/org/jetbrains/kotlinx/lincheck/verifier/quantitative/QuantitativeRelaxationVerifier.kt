/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
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
package org.jetbrains.kotlinx.lincheck.verifier.quantitative

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.verifier.AbstractLTSVerifier
import org.jetbrains.kotlinx.lincheck.verifier.LTSContext
import org.jetbrains.kotlinx.lincheck.verifier.get


/**
 * This verifier checks for quantitative relaxation contracts, which are introduced
 * in the "Quantitative relaxation of concurrent data structures" paper by Henzinger et al.
 *
 * Requires [QuantitativeRelaxationVerifierConf] annotation on the testing class.
 */
class QuantitativeRelaxationVerifier(scenario: ExecutionScenario, testClass: Class<*>) : AbstractLTSVerifier<ExtendedLTS.State>(scenario, testClass) {
    private val relaxationFactor: Int
    private val pathCostFunc: PathCostFunction
    private val lts: ExtendedLTS

    init {
        val conf = testClass.getAnnotation(QuantitativeRelaxationVerifierConf::class.java)
        requireNotNull(conf) { "No configuration for QuasiLinearizabilityVerifier found" }
        relaxationFactor = conf.factor
        pathCostFunc = conf.pathCostFunc
        lts = ExtendedLTS(conf.costCounter.java, relaxationFactor)
    }

    override fun createInitialContext(results: ExecutionResult): LTSContext<ExtendedLTS.State> =
            QuantitativeRelaxationContext(scenario, lts.initialState, results)

    private inner class QuantitativeRelaxationContext(
            scenario: ExecutionScenario,
            state: ExtendedLTS.State,
            executed: IntArray,
            val results: ExecutionResult,
            val iterativePathCostFunctionCounter: IterativePathCostFunctionCounter
    ) : LTSContext<ExtendedLTS.State>(scenario, state, executed) {

        constructor(scenario: ExecutionScenario, state: ExtendedLTS.State, results: ExecutionResult) :
                this(scenario, state, IntArray(scenario.threads + 2), results, pathCostFunc.createIterativePathCostFunctionCounter(relaxationFactor))

        override fun nextContexts(threadId: Int): List<QuantitativeRelaxationContext> {
            // Check if there are unprocessed actors in the specified thread
            if (isCompleted(threadId)) return emptyList()
            // Check whether an actor from the specified thread can be executed
            // in accordance with the rule that all actors from init part should be
            // executed at first, after that all actors from parallel part, and
            // all actors from post part should be executed at last.
            val legal = when (threadId) {
                0 -> true // INIT: we already checked that there is an unprocessed actor
                in 1..scenario.threads -> initCompleted // PARALLEL
                else -> initCompleted && parallelCompleted // POST
            }
            if (!legal) return emptyList()
            // Check whether the transition is possible in LTS.
            val i = executed[threadId]
            val actor = scenario[threadId][i]
            val result = results[threadId][i]
            if (actor.isRelaxed) {
                // Get list of possible transitions with their penalty costs.
                // Create a new context for each of them with an updated path cost function counters.
                val costWithNextCostCounterList = state.nextRelaxed(actor, result)
                return costWithNextCostCounterList.mapNotNull {
                    val nextPathCostFuncCounter = iterativePathCostFunctionCounter.next(it) ?: return@mapNotNull null
                    nextContext(threadId, lts.getStateForCostCounter(it.nextCostCounter), nextPathCostFuncCounter)
                }
            } else {
                // Get next state similarly to `LinearizabilityVerifier` and
                // create a new context with it and the same path cost function counter.
                val nextState = state.nextRegular(actor, result) ?: return emptyList()
                return listOf(nextContext(threadId, nextState, iterativePathCostFunctionCounter))
            }
        }

        private fun nextContext(threadId: Int, nextState: ExtendedLTS.State, nextIterativePathCostFuncCounter: IterativePathCostFunctionCounter): QuantitativeRelaxationContext {
            val nextExecuted = executed.copyOf()
            nextExecuted[threadId]++
            return QuantitativeRelaxationContext(scenario, nextState, nextExecuted, results, nextIterativePathCostFuncCounter)
        }

        private val Actor.isRelaxed get() = method.isAnnotationPresent(QuantitativeRelaxed::class.java)
    }
}