package org.jetbrains.kotlinx.lincheck.verifier.quasi

import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.get
import org.jetbrains.kotlinx.lincheck.verifier.*
import kotlin.math.abs
import kotlin.math.min

/**
 * This verifier checks that the specified results could happen in quasi-linearizable execution.
 * In order to do this it lazily constructs an execution graph using QuasiLinearizabilityContext
 */
class QuasiRelaxedLinearizabilityVerifier(sequentialSpecification: Class<*>) : AbstractLTSVerifier(sequentialSpecification) {
    override val lts: LTS
    private val quasiFactor: Int

    override fun createInitialContext(scenario: ExecutionScenario, results: ExecutionResult) =
            QuasiRelaxedLinearizabilityContext(scenario, results, lts.initialState, quasiFactor)

    init {
        val conf = sequentialSpecification.getAnnotation(QuasiLinearizabilityVerifierConf::class.java)
        checkNotNull(conf) { "QuasiLinearizabilityVerifierConf is not specified for the sequential specification " +
                "$sequentialSpecification, the verifier can not be initialised." }
        quasiFactor = conf.factor
        lts = LTS(
                sequentialSpecification = sequentialSpecification,
                relaxationType = RelaxationType.QUASI,
                relaxationFactor = quasiFactor
        )
    }
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

class QuasiRelaxedLinearizabilityContext : VerifierContext {
    private val quasiFactor: Int

    constructor(scenario: ExecutionScenario, results: ExecutionResult, state: LTS.State,
                quasiFactor: Int) : super(scenario, results, state) {
        this.quasiFactor = quasiFactor
        this.executedActors = Array(scenario.threads + 2) { mutableSetOf<Int>() }
        this.last = IntArray(scenario.threads + 2) { -1 }
        this.skipped = Array(scenario.threads + 2) { mutableMapOf<Int, Int>() }
    }
    constructor(scenario: ExecutionScenario, results: ExecutionResult, state: LTS.State,
                executed: IntArray, suspended: BooleanArray, tickets: IntArray,
                executedActors: Array<MutableSet<Int>>, last: IntArray, skipped: Array<MutableMap<Int, Int>>, quasiFactor: Int) :
            super(scenario, results, state, executed) {
        this.quasiFactor = quasiFactor
        this.executedActors = executedActors
        this.last = last
        this.skipped = skipped
    }

    /**
     * Sets of indices of executed actors for every thread
     */
    private val executedActors: Array<MutableSet<Int>>
    /**
     * Indices of the last executed actors for every thread
     */
    private val last: IntArray
    /**
     * Lateness of skipped actors for every thread
     */
    private val skipped: Array<MutableMap<Int, Int>>

    private fun executedInRange(from: Int, to: Int) = executed.slice(from .. to).sum()

    override fun nextContexts(threadId: Int): List<VerifierContext> {
        if (isCompleted(threadId)) return emptyList()
        val legalTransitions = mutableListOf<VerifierContext>()
        for (e in skipped[0]) {
            if (e.value == quasiFactor) {
                val actorId = e.key
                // the actor from initial part has maximal lateness -> it is the only one to executed
                val nextContext = state.nextContext(0, actorId)
                return if (nextContext != null) listOf(nextContext) else emptyList()
            }
        }
        // try to execute actors located within quasiFactor positions in execution scenario for this thread
        for (jump in 1..quasiFactor + 1) {
            // thread local position of a potential actor to be executed
            val localPos = last[threadId] + jump
            // check whether this actor is located within quasiFactor distance from its non-relaxed position of execution
            val nonRelaxedPos = getNonRelaxedActorPosition(threadId, localPos)
            val lateness = getGlobalLateness(threadId, nonRelaxedPos)
            if (lateness <= quasiFactor && localPos < scenario[threadId].size) {
                if (!executedActors[threadId].contains(localPos)) {
                    val nextContext = state.nextContext(threadId, localPos)
                    if (nextContext != null) legalTransitions.add(nextContext)
                }
            }
        }
        // try to execute skipped actors
        for (e in skipped[threadId]) {
            val actorId = e.key
            val nextContext = state.nextContext(threadId, actorId)
            if (nextContext != null) {
                if (e.value == quasiFactor) {
                    val nextContext = state.nextContext(threadId, actorId)
                    // this skipped actor has maximal lateness -> it is the only one to be executed
                    return if (nextContext != null) listOf(nextContext) else emptyList()
                }
                legalTransitions.add(nextContext)
            }
        }
        return legalTransitions
    }

    private fun TransitionInfo.createContext(threadId: Int, actorId: Int): QuasiRelaxedLinearizabilityContext {
        val nextExecuted = executed.copyOf()
        val nextSuspended = suspended.copyOf()
        val nextTickets = tickets.copyOf()
        val nextLast = last.copyOf()
        val nextExecutedActors = Array(scenario.threads + 2) { mutableSetOf<Int>() }
        val nextSkipped = Array(scenario.threads + 2) { mutableMapOf<Int, Int>() }
        for (i in 0..scenario.threads + 1) {
            nextExecutedActors[i].addAll(executedActors[i])
            nextSkipped[i].putAll(skipped[i])
        }
        nextExecuted[threadId]++
        nextExecutedActors[threadId].add(actorId)
        nextLast[threadId] = actorId
        if (nextSkipped[0].contains(actorId)) nextSkipped[0].remove(actorId)
        // update skipped actors
        if (actorId < last[threadId]) {
            // previously skipped actor was executed, so we can remove it from skipped actors
            nextSkipped[threadId].remove(actorId)
        } else {
            // the following actor or some actor from the future was executed out-of-order
            for (skippedActorId in last[threadId] + 1 until actorId) {
                // if an actor was executed out-of-order skipping some actors located after the last executed actor, than all these actors should be added to skipped
                // actual latenesses for these actors are counted below
                if (!nextExecutedActors[threadId].contains(skippedActorId)) {
                    nextSkipped[threadId][skippedActorId] = 0
                }
            }
        }
        // now update latenesses of skipped actors
        // update latenesses according to the global position of execution
        val globalPos = executedInRange(0, scenario.threads + 1)
        val it = nextSkipped[threadId].keys.iterator()
        while (it.hasNext()) {
            val skippedActorId = it.next()
            val nonRelaxedPos = getNonRelaxedActorPosition(threadId, skippedActorId)
            // lateness is only counted for actors that are behind the current global position of execution
            // others can still be executed in order and are not late now
            if (nonRelaxedPos < globalPos) {
                nextSkipped[threadId][skippedActorId] = getGlobalLateness(threadId, nonRelaxedPos)
            }
        }
        if (threadId == scenario.threads + 1) {
            // post part actor executed
            // in every thread the number of skipped actors equals the number of actors executed in post part
            // lateness of all these parallel part actors should be incremented
            for (t in 1..scenario.threads) {
                val lastSkippedActor = min(nextExecutedActors[scenario.threads + 1].size, scenario[t].size)
                for (skippedActorId in 0 until lastSkippedActor) {
                    if (!nextExecutedActors[t].contains(skippedActorId)) {
                        nextSkipped[t].putIfAbsent(skippedActorId, 0)
                        nextSkipped[t][skippedActorId]!!.inc()
                    }
                }
            }
        }
        if (threadId in 1..scenario.threads + 1) {
            // actor from non-initial part is executed
            // in initial thread the number of skipped actors equals the number of actors executed in parallel post parts
            // latenesses of all these initial part actors should be incremented
            val lastSkippedActor = min(nextExecutedActors[scenario.threads + 1].size, scenario[0].size)
            for (skippedActorId in 0 until lastSkippedActor) {
                if (!nextExecutedActors[0].contains(skippedActorId)) {
                    nextSkipped[0].putIfAbsent(skippedActorId, 0)
                    nextSkipped[0][skippedActorId]!!.inc()
                }
            }
        }
        return QuasiRelaxedLinearizabilityContext(
                scenario = scenario,
                state = nextState,
                results = results,
                executed = nextExecuted,
                suspended = nextSuspended,
                tickets = nextTickets,
                executedActors = nextExecutedActors,
                last = nextLast,
                skipped = nextSkipped,
                quasiFactor = quasiFactor
        )
    }

    private fun getNonRelaxedActorPosition(threadId: Int, localPos: Int) =
        when (threadId) {
            // non-relaxed position of an initial part actor = localPos
            0 -> localPos
            // non-relaxed position of a parallel part actor = (all initial part actors) + localPos
            in 1..scenario.threads -> (scenario[0].size + localPos)
            // non-relaxed position of a post part actor = (all initial and parallel part actors) + localPos
            else -> scenario.initExecution.size + scenario.parallelExecution.sumBy { it.size } + localPos
        }

    private fun getGlobalLateness(threadId: Int, nonRelaxedActorPos: Int): Int {
        // lateness = |[global position of execution] - [non-relaxed position of the actor]|
        val globalPos = executedInRange(0, scenario.threads + 1)
        return when (threadId) {
            // global position for initial and post part actors = executed actors from all parts
            0, scenario.threads + 1 -> abs(globalPos - nonRelaxedActorPos)
            // global position for parallel part actors = all initial and post part actors and actors executed in specified thread of parallel part
            else -> {
                val parallelGlobalPos = executed[0] + executed[threadId] + executed[scenario.threads + 1]
                abs(parallelGlobalPos - nonRelaxedActorPos)
            }
        }
    }

    private fun LTS.State.nextContext(threadId: Int, actorId: Int): QuasiRelaxedLinearizabilityContext? =
            next(scenario[threadId][actorId], results[threadId][actorId], NO_TICKET)?.createContext(threadId, actorId)
}