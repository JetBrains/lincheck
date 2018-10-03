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

package com.devexperts.dxlab.lincheck.verifier.quasi

import com.devexperts.dxlab.lincheck.execution.ExecutionResult
import com.devexperts.dxlab.lincheck.execution.ExecutionScenario
import com.devexperts.dxlab.lincheck.verifier.*
import com.devexperts.dxlab.lincheck.verifier.LTS.*
import kotlin.math.abs
import kotlin.math.min

/**
 * This verifier checks that the specified results could happen in quasi-linearizable execution.
 * In order to do this it lazily constructs an execution graph using QuasiLinearizabilityContext
 */
class QuasiLinearizabilityVerifier(scenario: ExecutionScenario, testClass: Class<*>) : AbstractLTSVerifier<RegularLTS.State>(scenario, testClass) {
    private val quasiFactor = testClass.getAnnotation(QuasiLinearizabilityVerifierConf::class.java).factor

    /**
     * Returns initial QuasiLinearizabilityContext
     */
    override fun createInitialContext(results: ExecutionResult): LTSContext<RegularLTS.State> = TODO()
}

/**
 * Quasi-linearizability allows execution to be within a bounded distance from linearizable execution
 * Look at this paper for details: Afek, Yehuda, Guy Korland, and Eitan Yanovsky.
 * "Quasi-linearizability: Relaxed consistency for improved concurrency."
 * International Conference on Principles of Distributed Systems. Springer, Berlin, Heidelberg, 2010.
 *
 * Next possible states are determined lazily by trying to execute not only actor that is next in order for a given thread
 * but also actors located within quasi-factor in execution scenario for this thread
 *
 * Current state of scenario execution is represented by information for every thread about executed actors and
 * those actors that will be executed out-of-order and skipped at the moment
 */
//private class QuasiLinearizabilityContext(scenario: ExecutionScenario, val results: ExecutionResult, val quasiFactor: Int) :
//        LTSContext<RegularLTS.State>(scenario) {
//
//    /**
//     * Represents position of an actor in execution scenario
//     */
//    data class ExecutionStep(val threadId: Int, val actorIndex: Int)
//
//    /**
//     * The set of executed actors' indices for every thread
//     */
//    private var executedActors = Array(scenario.threads + 2) { mutableSetOf<Int>() }
//    /**
//     * Indices of the last executed actors for every thread
//     */
//    private var last = IntArray(scenario.threads + 2) { -1 }
//    /**
//     * Latenesses of skipped actors for every thread
//     */
//    private var skipped = Array(scenario.threads + 2) { mutableMapOf<Int, Int>() }
//
//    private fun nextContext(actor: ExecutionStep): QuasiLinearizabilityContext {
//        val threadId = actor.threadId
//        val actorIndex = actor.actorIndex
//        val next = copy()
//        next.executed[threadId]++
//        next.executedActors[threadId].add(actorIndex)
//        next.last[threadId] = actorIndex
//        // update skipped actors
//        if (actorIndex < last[threadId]) {
//            // previously skipped actor was executed, so we can remove it from skipped actors
//            next.skipped[threadId].remove(actorIndex)
//        } else {
//            // the following actor or some actor from the future was executed out-of-order
//            for (skippedActorId in last[threadId] + 1 until actorIndex) {
//                // if an actor was executed out-of-order skipping some actors located after the last executed actor, than all these actors should be added to skipped
//                // actual latenesses for these actors are counted below
//                next.skipped[threadId][skippedActorId] = 0
//            }
//        }
//        // now update latenesses of skipped actors
//        // update latenesses according to the global position of execution
//        val globalPos = executedInRange(0, scenario.threads + 1)
//        val it = next.skipped[threadId].keys.iterator()
//        while (it.hasNext()) {
//            val skippedActorId = it.next()
//            val nonRelaxedPos = getNonRelaxedActorPosition(threadId, skippedActorId)
//            // latenesses are only counted for actors that are behind the current global position of execution
//            // others can still be executed in order and are not late now
//            if (nonRelaxedPos < globalPos) {
//                next.skipped[threadId][skippedActorId] = getGlobalLateness(threadId, nonRelaxedPos)
//            }
//        }
//        if (threadId == scenario.threads + 1) {
//            // post part actor executed
//            // in every thread the number of skipped actors equals the number of actors executed in post part
//            // latenesses of all these parallel part actors should be incremented
//            for (t in 1..scenario.threads) {
//                next.incThreadLatenesses(t, min(executedActors[scenario.threads + 1].size, scenario[t].size))
//            }
//        }
//
//        if (threadId in 1..scenario.threads + 1) {
//            // actor from non-initial part is executed
//            // in initial thread the number of skipped actors equals the number of actors executed in parallel post parts
//            // latenesses of all these initial part actors should be incremented
//            next.incThreadLatenesses(0, min(executedInRange(1, scenario.threads + 1), scenario[0].size))
//        }
//        return next
//    }
//
//    override fun nextContexts(threadId: Int): List<StateWithContext> {
//        val legalTransitions = mutableListOf<StateWithContext>()
//        for (e in skipped[0]) {
//            if (e.value == quasiFactor) {
//                val actor = ExecutionStep(0, e.key)
//                // initial part actor has maximal lateness -> it is the only one to executed
//                state.isTransitionLegal(actor)?.let { return listOf(StateWithContext(it, nextContext(actor))) }
//            }
//        }
//        // jump forward
//        for (jump in 1..quasiFactor + 1) {
//            // thread local position of a potential actor to be executed
//            val localPos = last[threadId] + jump
//            // check whether this actor is located within quasi-factor distance from its non-relaxed position of execution
//            val nonRelaxedPos = getNonRelaxedActorPosition(threadId, localPos)
//            val lateness = getGlobalLateness(threadId, nonRelaxedPos)
//            if (lateness <= quasiFactor && localPos < scenario[threadId].size) {
//                if (!executedActors[threadId].contains(localPos)) {
//                    val actor = ExecutionStep(threadId, localPos)
//                    state.isTransitionLegal(actor)?.let {
//                        val nextContext = nextContext(actor)
//                        legalTransitions.add(StateWithContext(it, nextContext))
//                    }
//                }
//            }
//        }
//        // try to execute skipped actors
//        for (e in skipped[threadId]) {
//            val actor = ExecutionStep(threadId, e.key)
//            val nextContext = nextContext(actor)
//            state.isTransitionLegal(actor)?.let {
//                if (e.value == quasiFactor) {
//                    // this skipped actor has maximal lateness -> it is the only one to be executed
//                    return listOf(StateWithContext(it, nextContext))
//                }
//                legalTransitions.add(StateWithContext(it, nextContext))
//            }
//        }
//        return legalTransitions
//    }
//
//    private fun getNonRelaxedActorPosition(threadId: Int, localPos: Int) =
//        when (threadId) {
//            // non-relaxed position of an initial part actor = localPos
//            0 -> localPos
//            // non-relaxed position of a parallel part actor = (all initial part actors) + localPos
//            in 1..scenario.threads -> (scenario[0].size + localPos)
//            // non-relaxed position of a post part actor = (all initial and parallel part actors) + localPos
//            else -> initialAndParallelActors + localPos
//        }
//
//    private fun getGlobalLateness(threadId: Int, nonRelaxedActorPos: Int): Int {
//        // lateness = |[global position of execution] - [non-relaxed position of the actor]|
//        val globalPos = executedInRange(0, scenario.threads + 1)
//        return when (threadId) {
//            // global position for initial and post part actors = executed actors from all parts
//            0, scenario.threads + 1 -> abs(globalPos - nonRelaxedActorPos)
//            // global position for parallel part actors = all initial and post part actors and actors executed in specified thread of parallel part
//            else -> {
//                val parallelGlobalPos = executed[0] + executed[threadId] + executed[scenario.threads + 1]
//                abs(parallelGlobalPos - nonRelaxedActorPos)
//            }
//        }
//    }
//
//
//    private fun incThreadLatenesses(threadId: Int, lastSkippedActor: Int) {
//        for (actorId in 0 until lastSkippedActor) {
//            if (!executedActors[threadId].contains(actorId)) {
//                skipped[threadId].putIfAbsent(actorId, 0)
//                skipped[threadId][actorId]!!.inc()
//            }
//        }
//    }
//
//    private fun copy(): QuasiLinearizabilityContext {
//        val next = QuasiLinearizabilityContext(scenario, results, lts, quasiFactor)
//        next.executed = executed.copyOf()
//        next.last = last.copyOf()
//        for (i in 0..scenario.threads + 1) {
//            next.executedActors[i].clear()
//            next.executedActors[i].addAll(executedActors[i])
//            next.skipped[i].clear()
//            next.skipped[i].putAll(skipped[i])
//        }
//        return next
//    }
//
//    private fun State.isTransitionLegal(es: ExecutionStep): State? {
//        this as RegularLTS.State
//        val actor = scenario[es.threadId][es.actorIndex]
//        val result = results[es.threadId][es.actorIndex]
//        val nextResState = next(actor)
//        if (nextResState.result == result) {
//            return nextResState.state
//        }
//        return null
//    }
//}
