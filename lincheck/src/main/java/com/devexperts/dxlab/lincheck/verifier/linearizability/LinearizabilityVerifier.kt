package com.devexperts.dxlab.lincheck.verifier.linearizability

/*
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

import com.devexperts.dxlab.lincheck.execution.ExecutionResult
import com.devexperts.dxlab.lincheck.execution.ExecutionScenario
import com.devexperts.dxlab.lincheck.verifier.*

/**
 * This verifier checks that the specified results could be happen in linearizable execution,
 * for what it tries to find a possible linear execution which transitions does not violate both
 * regular LTS (see [LTS] and [RegularLTS]) transitions and the happens-before order. Essentially,
 * it just tries to execute the next actor in each thread and goes deeper until all actors are executed.
 *
 * This verifier is based on [AbstractLTSVerifier] and caches the already processed results
 * for performance improvement (see [CachedVerifier]).
 */
class LinearizabilityVerifier(scenario: ExecutionScenario, testClass : Class<*>) : AbstractLTSVerifier<RegularLTS.State>(scenario, testClass) {
    override fun createInitialContext(results: ExecutionResult): LTSContext<RegularLTS.State>
            = LinearizabilityContext(scenario, RegularLTS(testClass).initialState, results)
}

/**
 * Next possible states are determined lazily by trying to execute next actor in order for every thread
 *
 * Current state of scenario execution is represented with the number of actors executed in every thread
 */
private class LinearizabilityContext(scenario: ExecutionScenario,
                                     state: RegularLTS.State,
                                     executed: IntArray,
                                     val results: ExecutionResult
) : LTSContext<RegularLTS.State>(scenario, state, executed) {

    constructor(scenario: ExecutionScenario, state: RegularLTS.State, results: ExecutionResult)
            : this(scenario, state, IntArray(scenario.threads + 2), results)

    override fun nextContexts(threadId: Int): List<LinearizabilityContext> {
        // Check if there are unprocessed actors in the specified thread
        if (isCompleted(threadId)) return emptyList()
        // Check whether an actor from the specified thread can be executed
        // in accordance with the rule that all actors from init part should be
        // executed at first, after that all actors from parallel part, and
        // all actors from post part should be executed at last.
        val legal = when (threadId) {
            0 -> true // INIT: we already checked that there is an unprocessed actor
            in 1 .. scenario.threads -> initCompleted // PARALLEL
            else -> initCompleted && parallelCompleted // POST
        }
        if (!legal) return emptyList()
        // Check whether the transition is possible in LTS
        val i = executed[threadId]
        val nextState = state.next(scenario[threadId][i], results[threadId][i]) ?: return emptyList()
        // The transition is possible, create a new context
        val nextExecuted = executed.copyOf()
        nextExecuted[threadId]++
        return listOf(LinearizabilityContext(scenario, nextState, nextExecuted, results))
    }
}
