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

package org.jetbrains.kotlinx.lincheck.verifier

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.Result
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.ExtendedLTS


/**
 * An abstraction for verifiers which use the labeled transition system (LTS) under the hood.
 * The main idea of such verifiers is finding a path in LTS, which starts from the initial
 * LTS state (see [LTS.initialState]) and goes through all actors with the specified results.
 * To determine which transitions are possible from the current state, we store related
 * to the current path prefix information in the special [LTSContext], which determines
 * the next possible transitions using [LTSContext.nextContexts] function. This verifier
 * uses depth-first search to find a proper path.
 */
abstract class AbstractLTSVerifier<STATE>(val scenario: ExecutionScenario, val testClass: Class<*>) : CachedVerifier() {
    abstract fun createInitialContext(results: ExecutionResult): LTSContext<STATE>

    override fun verifyResultsImpl(results: ExecutionResult): Boolean {
        return verify(createInitialContext(results))
    }

    private fun verify(context: LTSContext<STATE>): Boolean {
        // Check if a possible path is found.
        if (context.completed) return true
        // Traverse through next possible transitions using depth-first search (DFS). Note that
        // initial and post parts are represented as threads with ids `0` and `threads + 1` respectively.
        for (threadId in 0..scenario.threads + 1) {
            for (c in context.nextContexts(threadId)) {
                if (verify(c)) return true
            }
        }
        return false
    }
}


/**
 * Common interface for different labeled transition systems, which several correctness formalisms use.
 * Lin-Check widely uses LTS-based formalisms for verification, see [Verifier] implementations as examples.
 * Essentially, LTS provides an information of the possibility to do a transition from one state to another
 * by the specified actor with the specified result. Nevertheless, it should be extended and provide any additional
 * information, like the transition penalty in [ExtendedLTS].
 */
interface LTS<STATE> {
    /**
     * Returns the state corresponding to the initial state of the data structure.
     */
    val initialState: STATE
}


/**
 *  Reflects the current path prefix information and stores the current LTS state
 *  (which essentially indicates the data structure state) for a single step of a legal path search
 *  in LTS-based verifiers. It counts next possible transitions via [nextContexts] function.
 */
abstract class LTSContext<STATE>(
        /**
         * Current execution scenario.
         */
        val scenario: ExecutionScenario,
        /**
         * LTS state of this context
         */
        val state: STATE,
        /**
         * Number of executed actors in each thread. Note that initial and post parts
         * are represented as threads with ids `0` and `threads + 1` respectively.
         */
        val executed: IntArray)
{
    /**
     * Counts next possible states and the corresponding contexts if the specified thread is executed.
     */
    abstract fun nextContexts(threadId: Int): List<LTSContext<STATE>>

    // The total number of actors in init part of the execution scenario.
    val initActors: Int = scenario[0].size
    // The number of executed actors in the init part.
    val initExecuted: Int = executed[0]
    // `true` if all actors in the init part are executed.
    val initCompleted: Boolean = initActors == initExecuted

    // The total number of actors in parallel part of the execution scenario.
    val parallelActors: Int = scenario.parallelExecution.fold(0) { sum, t -> sum + t.size }
    // The number of executed actors in the parallel part.
    val parallelExecuted: Int = executed.slice(1..scenario.threads).fold(0) { sum, e -> sum + e }
    // `true` if all actors in the init part are executed.
    val parallelCompleted: Boolean = parallelActors == parallelExecuted

    // The total number of actors in post part of the execution scenario.
    val postActors: Int = scenario[scenario.threads + 1].size
    // The number of executed actors in the post part.
    val postExecuted: Int = executed[scenario.threads + 1]
    // `true` if all actors in the post part are executed.
    val postCompleted: Boolean = postActors == postExecuted

    // The total number of actors in the execution scenario.
    val totalActors: Int = initActors + parallelActors + postActors
    // The total number of executed actors.
    val totalExecuted: Int = initExecuted + parallelExecuted + postExecuted
    // `true` if all actors are executed and a legal path is found therefore.
    val completed: Boolean = totalActors == totalExecuted

    // Returns `true` if all actors in the specified thread are executed.
    fun isCompleted(threadId: Int) = executed[threadId] == scenario[threadId].size
}


/**
 * Returns scenario for the specified thread. Note that initial and post parts
 * are represented as threads with ids `0` and `threads + 1` respectively.
 */
operator fun ExecutionScenario.get(threadId: Int): List<Actor> = when (threadId) {
    0 -> initExecution
    threads + 1 -> postExecution
    else -> parallelExecution[threadId - 1]
}

/**
 * Returns results for the specified thread. Note that initial and post parts
 * are represented as threads with ids `0` and `threads + 1` respectively.
 */
operator fun ExecutionResult.get(threadId: Int): List<Result> = when (threadId) {
    0 -> initResults
    parallelResults.size + 1 -> postResults
    else -> parallelResults[threadId - 1]
}
