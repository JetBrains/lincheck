/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck.datastructures.verifier

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*

/**
 * An abstraction for verifiers which use the labeled transition system (LTS) under the hood.
 * The main idea of such verifiers is finding a path in LTS, which starts from the initial
 * LTS state (see [LTS.initialState]) and goes through all actors with the specified results.
 * To determine which transitions are possible from the current state, we store related
 * to the current path prefix information in the special [VerifierContext], which determines
 * the next possible transitions using [VerifierContext.nextContext] function. This verifier
 * uses depth-first search to find a proper path.
 */
abstract class AbstractLTSVerifier(protected val sequentialSpecification: Class<*>) : CachedVerifier() {
    abstract val lts: LTS
    abstract fun createInitialContext(scenario: ExecutionScenario, results: ExecutionResult): VerifierContext

    override fun verifyResultsImpl(scenario: ExecutionScenario, results: ExecutionResult) = createInitialContext(scenario, results).verify()

    private fun VerifierContext.verify(): Boolean {
        // Check if a possible path is found.
        if (completed) return true
        // Traverse through next possible transitions using depth-first search (DFS). Note that
        // initial and post parts are represented as threads with ids `0` and `threads + 1` respectively.
        for (threadId in threads) {
            val nextContext = nextContext(threadId)
            if (nextContext !== null && nextContext.verify()) return true
        }
        return false
    }

}

/**
 *  Reflects the current path prefix information and stores the current LTS state
 *  (which essentially indicates the data structure state) for a single step of a legal path search
 *  in LTS-based verifiers. It counts next possible transitions via [nextContext] function.
 */
abstract class VerifierContext(
    /**
     * Current execution scenario.
     */
    protected val scenario: ExecutionScenario,
    /**
     * Expected execution results
     */
    protected val results: ExecutionResult,
    /**
     * LTS state of this context
     */
    val state: LTS.State,
    /**
     * Number of executed actors in each thread.
     */
    protected val executed: IntArray = IntArray(scenario.nThreads),
    /**
     * For every scenario thread stores whether it is suspended or not.
     */
    protected val suspended: BooleanArray = BooleanArray(scenario.nThreads),
    /**
     * For every thread it stores a ticket assigned to the last executed actor by [LTS].
     * A ticket is assigned from the range (0 .. threads) to an actor that suspends it's execution,
     * after being resumed the actor is invoked with this ticket to complete it's execution.
     * If an actor does not suspend, the assigned ticket equals [NO_TICKET].
     */
    protected val tickets: IntArray = IntArray(scenario.nThreads) { NO_TICKET }
) {
    /**
     * Counts next possible states and the corresponding contexts if the specified thread is executed.
     */
    abstract fun nextContext(threadId: Int): VerifierContext?

    /**
     * Returns `true` if all actors in the specified thread are executed.
     */
    fun isCompleted(threadId: Int) =
        executed[threadId] == scenario.threads[threadId].size

    /**
     * Returns `true` if all threads completed their execution.
     */
    val completed: Boolean get() =
        completedThreads + suspendedThreads == scenario.nThreads

    /**
     * Range for all threads
     */
    val threads: IntRange get() = 0 until scenario.nThreads

    /**
     * The number of threads that expectedly suspended their execution.
     */
    private val suspendedThreads: Int get() =
        threads.count { t -> suspended[t] && results.threadsResultsWithClock[t][executed[t]].result === Suspended }

    /**
     * Returns the number of completed threads from the specified range.
     */
    private fun completedThreads(range: IntRange) = range.count { t -> isCompleted(t) }

    /**
     * Returns the number of completed scenario threads.
     */
    private val completedThreads: Int get() = completedThreads(threads)
}
