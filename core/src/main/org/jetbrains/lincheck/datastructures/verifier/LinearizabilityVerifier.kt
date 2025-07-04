/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck.datastructures.verifier

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.lincheck.datastructures.verifier.*

/**
 * This verifier checks that the specified results could happen if the testing operations are linearizable.
 * For that, it tries to find a possible sequential execution where transitions do not violate both
 * LTS (see [LTS]) transitions and the happens-before order. Essentially, it tries to execute the next actor
 * in each thread and goes deeper until all actors are executed.
 *
 * This verifier is based on [AbstractLTSVerifier] and caches the already processed results
 * for performance improvement (see [CachedVerifier]).
 */
class LinearizabilityVerifier(sequentialSpecification: Class<*>) : AbstractLTSVerifier(sequentialSpecification) {
    override val lts: LTS = LTS(sequentialSpecification = sequentialSpecification)

    override fun createInitialContext(scenario: ExecutionScenario, results: ExecutionResult) =
        LinearizabilityContext(scenario, results, lts.initialState)
}

class LinearizabilityContext : VerifierContext {
    constructor(scenario: ExecutionScenario, results: ExecutionResult, state: LTS.State) : super(
        scenario,
        results,
        state
    )

    constructor(
        scenario: ExecutionScenario, results: ExecutionResult, state: LTS.State,
        executed: IntArray, suspended: BooleanArray, tickets: IntArray
    ) : super(scenario, results, state, executed, suspended, tickets)

    override fun nextContext(threadId: Int): LinearizabilityContext? {
        if (isCompleted(threadId)) return null
        // Check whether an actorWithToken from the specified thread can be executed
        // in accordance with the rule that all actors from init part should be
        // executed at first, after that all actors from parallel part, and
        // all actors from post part should be executed at last.
        if (!hblegal(threadId))
            return null
        val actorId = executed[threadId]
        val actor = scenario.threads[threadId][actorId]
        // null result is not impossible here as if the execution has hung, we won't check its result
        val expectedResult = results.threadsResults[threadId][actorId]!!
        // Check whether the operation has been suspended and should be followed by cancellation
        val ticket = tickets[threadId]
        val promptCancel = actor.promptCancellation && ticket != NO_TICKET && expectedResult === Cancelled
        if (suspended[threadId] || promptCancel) {
            return if (actor.cancelOnSuspension && expectedResult === Cancelled)
                state.nextByCancellation(actor, ticket).createContext(threadId)
            else null
        }
        // Try to make a transition by the next actor from the current thread,
        // passing the ticket corresponding to the current thread.
        return state.next(actor, expectedResult, tickets[threadId])?.createContext(threadId)
    }

    // checks whether the transition does not violate the happens-before relation constructed on the clocks
    private fun hblegal(threadId: Int): Boolean {
        val actorId = executed[threadId]
        val clocks = results.threadsResultsWithClock[threadId][actorId].clockOnStart
        for (i in 0 until scenario.nThreads) {
            if (executed[i] < clocks[i]) return false
        }
        return true
    }

    private fun TransitionInfo.createContext(threadId: Int): LinearizabilityContext {
        val nextExecuted = executed.copyOf()
        val nextSuspended = suspended.copyOf()
        val nextTickets = tickets.copyOf()
        // update tickets
        nextTickets[threadId] = if (result == Suspended) ticket else NO_TICKET
        if (rf != null) { // remapping
            nextTickets.forEachIndexed { tid, ticket ->
                if (tid != threadId && ticket != NO_TICKET)
                    nextTickets[tid] = rf[ticket]
            }
        }
        // update "suspended" statuses
        nextSuspended[threadId] = result == Suspended
        for (tid in threads) {
            if (nextTickets[tid] in resumedTickets) // note, that we have to use remapped tickets here!
                nextSuspended[tid] = false
        }
        // mark this step as "executed" if the operation was not suspended or is cancelled
        if (operationCompleted) nextExecuted[threadId]++
        // create new context
        return LinearizabilityContext(
            scenario = scenario,
            state = nextState,
            results = results,
            executed = nextExecuted,
            suspended = nextSuspended,
            tickets = nextTickets
        )
    }
}




