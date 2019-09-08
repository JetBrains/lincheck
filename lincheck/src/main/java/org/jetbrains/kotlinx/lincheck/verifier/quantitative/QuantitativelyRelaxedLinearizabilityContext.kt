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
package org.jetbrains.kotlinx.lincheck.verifier.quantitative

import org.jetbrains.kotlinx.lincheck.NoResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.verifier.LTS
import org.jetbrains.kotlinx.lincheck.verifier.TransitionInfo
import org.jetbrains.kotlinx.lincheck.verifier.VerifierContext
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.verifier.isRelaxed
import java.lang.IndexOutOfBoundsException

class QuantitativelyRelaxedLinearizabilityContext(
    scenario: ExecutionScenario,
    state: LTS.State,
    executed: IntArray,
    private val expectedResults: ExecutionResult,
    /**
     * For every scenario thread stores whether it is suspended or not.
     */
    private val suspended: BooleanArray,
    /**
     * For every thread it stores a ticket assigned to the last executed actor by [LTS].
     * A ticket is assigned from the range (0 .. threads) to an actor that suspends it's execution,
     * after being resumed the actor is invoked with this ticket to complete it's execution.
     * If an actor does not suspend, the assigned ticket equals `-1`.
     */
    private val tickets: IntArray,
    /**
     * Path cost counter
     */
    private val iterativePathCostFunctionCounter: IterativePathCostFunctionCounter
) : VerifierContext<LTS.State>(scenario, state, executed) {

    constructor (scenario: ExecutionScenario, state: LTS.State, results: ExecutionResult, relaxationFactor: Int, pathCostFunc: PathCostFunction)
        : this(
        scenario,
        state,
        executed = IntArray(scenario.threads + 2),
        expectedResults = results,
        suspended = BooleanArray(scenario.threads + 2),
        tickets = IntArray(scenario.threads + 2) { -1 },
        iterativePathCostFunctionCounter = pathCostFunc.createIterativePathCostFunctionCounter(relaxationFactor)
    )

    /**
     * The number of threads that expectedly suspended their execution.
     */
    private val suspendedThreads: Int
        get() = (0..scenario.threads + 1).count { t -> suspended[t] && expectedResults[t][executed[t]] is NoResult }

    /**
     * Returns `true` if all threads are either completed or suspended their execution.
     */
    override val completed: Boolean get() = completedThreads + suspendedThreads == scenario.threads + 2

    override fun nextContexts(threadId: Int): List<VerifierContext<LTS.State>> {
        // Check whether the specified thread is not suspended and there are unprocessed actors
        if (isCompleted(threadId) || suspended[threadId]) return emptyList()
        // Check whether an actorWithToken from the specified thread can be executed
        // in accordance with the rule that all actors from init part should be
        // executed at first, after that all actors from parallel part, and
        // all actors from post part should be executed at last.
        val legal = when (threadId) {
            0 -> true // INIT: we already checked that there is an unprocessed actorWithToken
            in 1..scenario.threads -> isCompleted(0) // PARALLEL
            else -> initCompleted && parallelCompleted // POST
        }
        if (!legal) return emptyList()
        val actorId = executed[threadId]
        val actor = scenario[threadId][actorId]
        val expectedResult = expectedResults[threadId][actorId]
        // Try to make transition by the next actor from the current thread,
        // passing the ticket corresponding to the current thread.
        return if (!actor.isRelaxed) {
            val nextTransition = state.next(actor, expectedResult, tickets[threadId])
            if (nextTransition != null) {
                listOf(nextTransition.createContext(threadId))
            } else emptyList()
        } else {
            val nextTransitions = state.nextRelaxed(actor, expectedResult, tickets[threadId])
            nextTransitions?.mapNotNull { transition ->
                val nextPathCostFuncCounter =
                    iterativePathCostFunctionCounter.next(transition.cost, transition.predicate) ?: return@mapNotNull null
                transition.createContext(threadId, nextPathCostFuncCounter)
            } ?: emptyList()
        }
    }

    private fun TransitionInfo.createContext(
        t: Int,
        nextPathCostFuncCounter: IterativePathCostFunctionCounter = iterativePathCostFunctionCounter
    ): QuantitativelyRelaxedLinearizabilityContext {
        val nextExecuted = executed.copyOf()
        val nextSuspended = suspended.copyOf()
        val nextTickets = tickets.copyOf()

        // remap tickets
        if (rf != null && rf.isNotEmpty()) {
            nextTickets.forEachIndexed { thId, ticket ->
                if (ticket != -1 &&
                    // The current thread ticket may not be present in the remapping function
                    // if the executed actor was resumed.
                    ticket < rf.size
                ) nextTickets[thId] = rf[ticket]
            }
        }
        if (wasSuspended) {
            // Request execution suspended the current thread.
            nextSuspended[t] = true
            // Map the current thread to the ticket assigned to this request by LTS.
            nextTickets[t] = ticket
        } else {
            // The current thread was not suspended.
            nextSuspended[t] = false
            nextExecuted[t]++
            nextTickets[t] = -1
            // Mark all resumed threads available for execution.
            for (susT in nextSuspended.indices) {
                if (nextTickets[susT] in resumedTickets) {
                    nextSuspended[susT] = false
                }
            }
        }
        return QuantitativelyRelaxedLinearizabilityContext(
            scenario,
            state = nextState,
            expectedResults = expectedResults,
            executed = nextExecuted,
            suspended = nextSuspended,
            tickets = nextTickets,
            iterativePathCostFunctionCounter = nextPathCostFuncCounter
        )
    }
}
