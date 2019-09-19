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

package org.jetbrains.kotlinx.lincheck.verifier

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.*
import org.jetbrains.kotlinx.lincheck.verifier.LTS.*
import java.lang.reflect.Method
import java.util.*
import kotlin.collections.HashMap
import kotlin.coroutines.*
import kotlin.math.max


/**
 * An abstraction for verifiers which use the labeled transition system (LTS) under the hood.
 * The main idea of such verifiers is finding a path in LTS, which starts from the initial
 * LTS state (see [LTS.initialState]) and goes through all actors with the specified results.
 * To determine which transitions are possible from the current state, we store related
 * to the current path prefix information in the special [VerifierContext], which determines
 * the next possible transitions using [VerifierContext.nextContexts] function. This verifier
 * uses depth-first search to find a proper path.
 */
abstract class AbstractLTSVerifier<STATE>(protected val scenario: ExecutionScenario, protected val sequentialSpecification: Class<*>) : CachedVerifier() {
    abstract val lts: LTS
    abstract fun createInitialContext(results: ExecutionResult): VerifierContext<STATE>

    override fun verifyResultsImpl(results: ExecutionResult) = verify(createInitialContext(results))

    private fun verify(context: VerifierContext<STATE>): Boolean {
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

    override fun checkStateEquivalenceImplementation() = lts.checkStateEquivalenceImplementation()
}

typealias RemappingFunction = IntArray
typealias ResumedTickets = Set<Int>

/**
 * Common interface for different labeled transition systems, which several correctness formalisms use.
 * Lincheck widely uses LTS-based formalisms for verification, see [AbstractLTSVerifier] implementations as examples.
 * Essentially, LTS provides an information of the possibility to do a transition from one state to another
 * by the specified operation with the specified result.
 *
 * In order not to construct the full LTS (which is impossible because it can be either infinite
 * or just too big to build), we construct it lazily during the requests and reuse it between runs.
 *
 * The current implementation of [LTS] supports two kinds of transitions: non-relaxed and relaxed.
 * A non-relaxed transition from the specified state by the specified operation
 * determines the possible result and the next state uniquely. Taking this into account,
 * we internally represent non-relaxed transitions as [[State] x [Actor] x [TransitionInfo]].
 *
 * On the contrary, a relaxed transition from the specified state by the specified operation may lead to
 * several possible results and corresponding next states. Relaxed transitions are internally
 * represented as [[State] x [Actor] x List<[RelaxedTransitionInfo]>].
 *
 * The current LTS implementation supports transitions by partial operations that may register their request and
 * block it's execution waiting for some precondition to become true. Partial operations are presented in
 * "Nonblocking concurrent objects with condition synchronization" paper by Scherer III W., Scott M.
 *
 * Practically, Kotlin implementation of such operations via suspend functions is supported.
 */

class LTS(
    private val sequentialSpecification: Class<*>,
    private val isQuantitativelyRelaxed: Boolean,
    private val relaxationFactor: Int
) {
    /**
     * Cache with all LTS states in order to reuse the equivalent ones.
     * Equivalency relation among LTS states is defined by the [StateInfo] class.
     */
    private val stateInfos = HashMap<StateInfo, StateInfo>()

    val initialState: State = createInitialState()

    /**
     * [LTS] state is defined by the sequence of operations that lead to this state from the [initialState].
     * Every state stores non-relaxed [transitions] and relaxed [relaxedTransitions]
     * that are counted lazily during the requests.
     */
    inner class State(var seqToCreate: List<Operation>, private val costCounter: Any? = null) {
        private val transitions = mutableMapOf<ActorWithTicket, TransitionInfo?>()
        private val relaxedTransitions = mutableMapOf<Operation, List<RelaxedTransitionInfo>?>()

        /**
         * Counts or gets the existing non-relaxed transition from the current state
         * by the given [actor].
         */
        fun next(actor: Actor, expectedResult: Result, requestTicket: Int): TransitionInfo? {
            val isRequest = requestTicket == -1
            val operation = Operation(actor, expectedResult, requestTicket, isRequest)
            val key = ActorWithTicket(actor, requestTicket, isRequest)
            val resultWithTransitionInfo = if (transitions.contains(key)) {
                transitions[key]
            } else {
                val resInfo = generateNextState(operation) { result, instance, suspendedOperations, resumedOperations ->
                    createTransition(operation, result, instance, suspendedOperations, resumedOperations)
                }
                if (resInfo != null) transitions[key.also { it.ticket = resInfo.ticket }] = resInfo
                resInfo
            }
            return if (isQuantitativelyRelaxed) resultWithTransitionInfo
            else if (resultWithTransitionInfo!!.result == expectedResult ||
                // Allow transition by a suspended request
                // regardless whether the operation was suspended during test running or not,
                // thus allowing elimination of interblocking operations in the implementation.
                (isRequest && resultWithTransitionInfo.result is NoResult)
            )
                resultWithTransitionInfo
            else
                null
        }

        /**
         * Counts or gets the existing relaxed transition from the current state
         * by the given [actor] and [expectedResult].
         */
        fun nextRelaxed(actor: Actor, expectedResult: Result, requestTicket: Int): List<RelaxedTransitionInfo>? {
            val operation = Operation(actor, expectedResult, requestTicket, requestTicket == -1)
            return relaxedTransitions.computeIfAbsent(operation) {
                generateNextState(operation) { result, instance, suspendedOperations, resumedOperations ->
                    createRelaxedTransitions(operation, result, suspendedOperations, resumedOperations)
                }
            }
        }

        private inline fun <T> generateNextState(
            nextOperation: Operation,
            action: (
                result: Result,
                instance: Any,
                suspendedOperations: List<Operation>,
                resumedOperations: List<ResumptionInfo>
            ) -> T
        ): T {
            // Copy the state by sequentially applying operations from seqToCreate.
            val instance = if (isQuantitativelyRelaxed) costCounter!! else sequentialSpecification.newInstance()
            val suspendedOperations = mutableListOf<Operation>()
            val resumedTicketsWithResults = mutableMapOf<Int, ResumedResult>()
            try {
                for (operation in seqToCreate) {
                    operation.invoke(instance, suspendedOperations, resumedTicketsWithResults)
                }
            } catch (e: Exception) {
                throw  IllegalStateException(e)
            }
            // Invoke the given operation to count the next transition.
            val result = nextOperation.invoke(instance, suspendedOperations, resumedTicketsWithResults)
            return action(result, instance, suspendedOperations, getResumedOperations(resumedTicketsWithResults))
        }

        private fun createTransition(
            operation: Operation,
            result: Result,
            instance: Any,
            suspendedOperations: List<Operation>,
            resumedOperations: List<ResumptionInfo>
        ): TransitionInfo? {
            val costCounterOrTestInstance =
                if (!isQuantitativelyRelaxed) instance
                else {
                    if (result is ValueResult && result.value != null) {
                        // Standalone instance of CostCounter is returned.
                        check(result.value.javaClass == sequentialSpecification) {
                            "Non-relaxed ${operation.actor} should store transitions within CostCounter instances, but ${result.value} is found"
                        }
                        result.value
                    } else {
                        // The impossibility of this transition is already defined by CostCounter class.
                        return null
                    }
                }
            val stateInfo = StateInfo(this, costCounterOrTestInstance, suspendedOperations, resumedOperations)
            return stateInfo.intern(curOperation = operation) { stateInfo, rf ->
                TransitionInfo(
                    nextState = stateInfo.state,
                    resumedTickets = stateInfo.resumedOperations.map { it.resumedActorTicket }.toSet(),
                    wasSuspended = result is NoResult,
                    ticket = if (rf != null && result is NoResult) rf[operation.ticket] else operation.ticket,
                    rf = rf,
                    result = result
                )
            }
        }

        private fun createRelaxedTransitions(
            operation: Operation,
            result: Result,
            suspendedOperations: List<Operation>,
            resumedOperations: List<ResumptionInfo>
        ): List<RelaxedTransitionInfo>? {
            val nextCostCounterInstances = if (result is ValueResult && result.value != null) {
                // A list of CostWithNextCounter instances is returned.
                check(result.value is List<*>) {
                    "Relaxed ${operation.actor} should store transitions within a list of CostWithNextCostCounter, but ${result.value} is found"
                }
                result.value as List<CostWithNextCostCounter<*>>
            } else {
                return null
            }
            return nextCostCounterInstances.map { it ->
                val stateInfo = StateInfo(this, it.nextCostCounter, suspendedOperations, resumedOperations)
                stateInfo.intern(curOperation = operation) { stateInfo, rf ->
                    RelaxedTransitionInfo(
                        nextState = stateInfo.state,
                        resumedTickets = stateInfo.resumedOperations.map { it.resumedActorTicket }.toSet(),
                        wasSuspended = result is NoResult,
                        ticket = operation.ticket,
                        rf = rf,
                        result = result,
                        cost = it.cost,
                        predicate = it.predicate
                    )
                }
            }
        }

        private fun getResumedOperations(resumedTicketsWithResults: Map<Int, ResumedResult>): List<ResumptionInfo> {
            val resumedOperations = mutableListOf<ResumptionInfo>()
            resumedTicketsWithResults.forEach { resumedTicket, res ->
                resumedOperations.add(ResumptionInfo(res.resumedActor, res.by!!).also { it.resumedActorTicket = resumedTicket })
            }
            // Ignore the order of resumption by sorting the list of resumptions.
            return resumedOperations.sortedBy { it.resumedActorTicket }
        }

    }

    private fun Operation.invoke(
        externalState: Any,
        suspendedOperations: MutableList<Operation>,
        resumedTicketsWithResults: MutableMap<Int, ResumedResult>
    ): Result {
        val prevResumedTickets = mutableListOf<Int>().also { it.addAll(resumedTicketsWithResults.keys) }
        val res = if (resumedTicketsWithResults.containsKey(ticket)) {
            // The given operation is a followUp available for execution.
            val (cont, suspensionPointRes) = resumedTicketsWithResults[ticket]!!.contWithSuspensionPointRes
            val finalRes = (
                if (cont == null) suspensionPointRes // Resumed operation has no follow-up.
                else {
                    cont.resumeWith(suspensionPointRes)
                    resumedTicketsWithResults[ticket]!!.contWithSuspensionPointRes.second
                })
            resumedTicketsWithResults.remove(ticket)
            createLinCheckResult(finalRes, wasSuspended = true)
        } else {
            if (ticket == -1) ticket = findFirstAvailableTicket(suspendedOperations, resumedTicketsWithResults)
            executeActor(
                externalState,
                actor,
                Completion(ticket, actor, resumedTicketsWithResults),
                if (isQuantitativelyRelaxed) result else null
            )
        }
        if (res is NoResult) {
            // Operation suspended it's execution.
            suspendedOperations.add(this)
        } else {
            resumedTicketsWithResults.forEach { resumedTicket, res ->
                if (!prevResumedTickets.contains(resumedTicket)) {
                    suspendedOperations.removeIf { it.ticket == resumedTicket }
                    res.by = actor
                }
            }
        }
        return res
    }

    /**
     * Creates and stores the new LTS state or gets the one if already exists.
     */
    private fun <T> StateInfo.intern(
        curOperation: Operation?,
        block: (StateInfo, RemappingFunction?) -> T
    ): T {
        return if (stateInfos.containsKey(this)) {
            val old = stateInfos[this]!!
            block(old, this.computeRemappingFunction(old))
        } else {
            val newSeqToCreate = if (curOperation != null) this.state.seqToCreate + curOperation else emptyList()
            stateInfos[this] = this.also { it.state = State(newSeqToCreate, if (isQuantitativelyRelaxed) instance else null) }
            return block(stateInfos[this]!!, null)
        }
    }

    private fun createInitialState(): State {
        val instance = createInitialStateInstance()
        return StateInfo(
            state = State(emptyList(), if (isQuantitativelyRelaxed) instance else null),
            instance = instance,
            suspendedOperations = emptyList(),
            resumedOperations = emptyList()
        ).intern(curOperation = null) { stateInfo, rf -> return@intern stateInfo.state }
    }

    private fun createInitialStateInstance() = if (isQuantitativelyRelaxed) createCostCounterInstance(sequentialSpecification, relaxationFactor)
                                               else sequentialSpecification.newInstance()

    fun checkStateEquivalenceImplementation() {
        val i1 = createInitialStateInstance()
        val i2 = createInitialStateInstance()
        check(i1.hashCode() == i2.hashCode() && i1 == i2) {
            "equals() and hashCode() methods for this test are not defined or defined incorrectly.\n" +
            "It is more convenient to make the sequential specification  class extend `VerifierState` class " +
            "and override the `extractState()` function to define both equals() and hashCode() methods.\n" +
            "This check may be suppressed by setting the `requireStateEquivalenceImplementationCheck` option to false."
        }
    }

    private fun StateInfo.computeRemappingFunction(old: StateInfo): RemappingFunction {
        val rf = IntArray(maxTicket + 1) { -1 }
        // Remap tickets of suspended operations according the order of suspension.
        for (i in suspendedOperations.indices) {
            rf[suspendedOperations[i].ticket] = old.suspendedOperations[i].ticket
        }
        // Remap tickets of resumed operations corresponding to equal resumption information.
        for (i in resumedOperations.indices) {
            rf[resumedOperations[i].resumedActorTicket] = old.resumedOperations[i].resumedActorTicket
        }
        return rf
    }


    private fun findFirstAvailableTicket(suspendedOperations: List<Operation>, resumedTicketsWithResults: MutableMap<Int, ResumedResult>): Int {
        for (ticket in 0 until suspendedOperations.size + resumedTicketsWithResults.size) {
            if (suspendedOperations.find { it.ticket == ticket } == null &&
                !resumedTicketsWithResults.contains(ticket)) {
                return ticket
            }
        }
        return suspendedOperations.size + resumedTicketsWithResults.size
    }
}

/**
 * Defines equivalency relation among LTS states (see [LTS.State]).
 * Stores the [state], and additional information about this state:
 * corresponding test [instance], execution status of operations that were used to create the given [state].
 *
 * The following state properties were chosen to define the state equivalency:
 *   1. The test instance corresponding to the given state with `hashCode` and `equals` defined by the user.
 *   2. The list of actor `requests` suspended on the LTS path to the [state]
 *   3. The set of pairs of resumed and the corresponding resuming actors.
 */
private class StateInfo(
    var state: LTS.State,
    val instance: Any,
    val suspendedOperations: List<Operation>,
    val resumedOperations: List<ResumptionInfo>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as StateInfo
        return instance == that.instance &&
            suspendedOperations.map { it.actor } == that.suspendedOperations.map { it.actor } &&
            resumedOperations == that.resumedOperations
    }

    override fun hashCode(): Int {
        return Objects.hash(
            instance,
            suspendedOperations.map { it.actor },
            resumedOperations
        )
    }

    val maxTicket: Int
        get() = max(
            suspendedOperations.maxBy { it.ticket }?.ticket ?: 0,
            resumedOperations.maxBy { it.resumedActorTicket }?.resumedActorTicket ?: 0
        )
}

private val relaxedActors = IdentityHashMap<Method, Boolean>()
val Actor.isRelaxed: Boolean
    get() = relaxedActors.computeIfAbsent(this.method) {
        this.method.isAnnotationPresent(QuantitativeRelaxed::class.java)
    }

/**
 * When suspended operation is resumed by another thread,
 * [VerifierInterceptor.interceptContinuation] is called to intercept it's continuation.
 * Intercepted continuation just writes the result of the suspension point and reference to the unintercepted continuation
 * so that the calling thread could resume this continuation by itself.
 */
internal class VerifierInterceptor(
    private val ticket: Int,
    private val actor: Actor,
    private val resumedTicketsWithResults: MutableMap<Int, ResumedResult>
) : AbstractCoroutineContextElement(ContinuationInterceptor),
    ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        return Continuation(EmptyCoroutineContext) { res ->
            resumedTicketsWithResults[ticket] =
                ResumedResult(continuation as Continuation<Any?> to res).also { it.resumedActor = actor }
        }
    }
}

/**
 * Passed as an argument to invoke suspendable operation
 *
 * [context] contains `ContinuationInterceptor` that intercepts continuation of the resumed operation
 * separating `request` and `follow-up` parts.
 *
 * If the operation has `follow-up` phase then [resumeWith] is executed at the end of the `follow-up`
 * writing the final result of the operation. Otherwise if there is no `follow-up`,
 * meaning that result of the suspension point equals the final result of the operation,
 * [resumeWith] will be executed just after resumption by the resuming thread.
 */
internal class Completion(
    private val ticket: Int,
    private val actor: Actor,
    private val resumedTicketsWithResults: MutableMap<Int, ResumedResult>
) : Continuation<Any?> {
    override val context: CoroutineContext
        get() = VerifierInterceptor(ticket, actor, resumedTicketsWithResults)

    override fun resumeWith(result: kotlin.Result<Any?>) {
        resumedTicketsWithResults[ticket] =
            ResumedResult(null as Continuation<Any?>? to result).also { it.resumedActor = actor }
    }
}

/**
 * The regular `Operation` is represented as a pair of [Actor] and [Result].
 * To support partial methods `Operation` should be extended.
 * A partial operation at first registers it's `request` and then waits for the fulfillment
 * of some precondition to become true to complete it's execution.
 * To be able to make transition by a blocking partial operation
 * it is divided into `request` and `followUp` parts as follows:
 * ```
 * [actor:result] = [actor_request : ticket] + [actor_followUp(ticket) : result]`
 * ```
 * If the required precondition is not yet set, the `request` part of an operation
 * suspends it's execution returning a [ticket] that will later be used to invoke completion
 * of the corresponding followUp.
 * Otherwise, the `request` part completes immediately returning the final result.
 *
 * Example of partial operations:
 * Assume, the rendezvous channel. There are two types of processes:
 * producers that perform `send` requests and consumers, which perform `receive` requests.
 * In order for a producer to `send` an element, it has to perform a rendezvous
 * handshake with a consumer, that will get this element. The consumer semantic is symmetrical.
 *
 * ```
 *  class Channel {
 *      fun send(e: Any)
 *      fun receive(): Any
 *   }
 * ```
 * To make sequential transitions by the blocking `send` and `receive` operations
 * they are divided as was described as above:
 * ```
 * 1. [receive_request():ticket] // `receive` registers it's request and suspends the execution
 * 2. [send_request(1):void]     // `send`'s `request` part completes without suspension as the receiver is already waiting
 * 3. [receive_request(ticket):res] // `receive` completes it's execution
 * ```
 */
data class Operation(
    val actor: Actor,
    val result: Result,
    var ticket: Int,
    val isRequest: Boolean
)

private data class ActorWithTicket(val actor: Actor, var ticket: Int, val isRequest: Boolean)

private data class ResumptionInfo(
    val resumedActor: Actor,
    val by: Actor
) {
    var resumedActorTicket = -1
}

/**
 * Describes non-relaxed transition.
 */
open class TransitionInfo(
    /**
     * The next LTS state.
     */
    val nextState: State,
    /**
     * The set of tickets corresponding to resumed operation requests which follow-ups are available to be invoked.
     */
    val resumedTickets: ResumedTickets,
    /**
     * Whether the current transition was made by the suspended request.
     */
    val wasSuspended: Boolean,
    /**
     * The ticket assigned to the transition operation.
     *
     * If the executed operation was the `request` part that suspended it's execution,
     * the first available ticket is assigned.
     * Otherwise, ticket equals `-1`.
     */
    val ticket: Int,
    /**
     * Ticket remapping function.
     * rf is not `null` if [nextState] was already present in LTS.
     * Stores correspondence between tickets assigned to operations on the current way to this state.
     * and previously assigned tickets.
     */
    val rf: RemappingFunction?,
    /**
     * Transition result
     */
    val result: Result
)

/**
 * Describes relaxed transition.
 */
class RelaxedTransitionInfo(
    nextState: State,
    resumedTickets: Set<Int>,
    wasSuspended: Boolean,
    ticket: Int,
    rf: RemappingFunction?,
    result: Result,
    /**
     * Transition cost
     */
    val cost: Int,
    /**
     * Transition predicate
     */
    val predicate: Boolean
) : TransitionInfo(nextState, resumedTickets, wasSuspended, ticket, rf, result)


/**
 *  Reflects the current path prefix information and stores the current LTS state
 *  (which essentially indicates the data structure state) for a single step of a legal path search
 *  in LTS-based verifiers. It counts next possible transitions via [nextContexts] function.
 */

abstract class VerifierContext<STATE>(
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
    val executed: IntArray
) {
    /**
     * Counts next possible states and the corresponding contexts if the specified thread is executed.
     */
    abstract fun nextContexts(threadId: Int): List<VerifierContext<STATE>>

    /**
     * Returns `true` if all actors in the specified thread are executed.
     */
    fun isCompleted(threadId: Int) = executed[threadId] == scenario[threadId].size

    /**
     * Returns the number of threads from the range [[tidFrom]..[tidTo]] (inclusively) which are completed.
     */
    private fun completedThreads(tidFrom: Int, tidTo: Int) = (tidFrom..tidTo).count { t -> isCompleted(t) }

    /**
     * Returns the number of completed scenario threads.
     */
    val completedThreads: Int get() = completedThreads(0, scenario.threads + 1)

    /**
     * Returns `true` if the initial part is completed.
     */
    val initCompleted: Boolean get() = isCompleted(0)

    /**
     * Returns `true` if all actors from the parallel scenario part are executed.
     */
    val parallelCompleted: Boolean get() = completedThreads(1, scenario.threads) == scenario.threads

    /**
     * Returns `true` if all threads completed their execution.
     */
    abstract val completed: Boolean
}

