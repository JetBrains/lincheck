/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.ensure


/**
 * Execution represents a set of events belonging to a single program's execution.
 *
 * The set of events in the execution is causally closed, meaning that if
 * an event belongs to the execution, all its causal predecessors must also belong to it.
 *
 * We assume that all the events within the same thread are totally ordered by the program order.
 * Thus, the execution is represented as a list of threads,
 * with each thread being a list of thread's events given in program order.
 *
 * Since we also assume that the program order is consistent the enumeration order of events,
 * (that is, `(a, b) \in po` implies `a.id < b.id`),
 * the threads' events lists are sorted with respect to the enumeration order.
 *
 * @param E the type of events stored in the execution, must extend `ThreadEvent`.
 */
interface Execution<out E : ThreadEvent> : Collection<E> {

    /**
     * A map from thread IDs to the list of thread events.
     */
    val threadMap: ThreadMap<SortedList<E>>

    /**
     * Retrieves the list of events for the specified thread ID.
     *
     * @param tid The thread ID for which to retrieve the events.
     * @return The list of events for the specified thread ID,
     *  or null if the requested thread does not belong to the execution.
     */
    operator fun get(tid: ThreadId): SortedList<E>? =
        threadMap[tid]

    /**
     * Checks if the given event is present at the execution.
     *
     * @param element The event to check for presence in the execution.
     * @return true if the execution contains the event, false otherwise.
     */
    override fun contains(element: @UnsafeVariance E): Boolean =
        get(element.threadId, element.threadPosition) == element

    /**
     * Checks if all events in the given collection are present at the execution.
     *
     * @param elements The collection of events to check for presence in the execution.
     * @return true if all events in the collection are present in the execution, false otherwise.
     */
    override fun containsAll(elements: Collection<@UnsafeVariance E>): Boolean =
        elements.all { contains(it) }


    /**
     * Returns an iterator over the events in the execution.
     *
     * @return an iterator that iterates over the events of the execution.
     */
    override fun iterator(): Iterator<E> =
        threadIDs.map { get(it)!! }.asSequence().flatten().iterator()

}

/**
 * Mutable execution represents a modifiable set of events belonging to a single program's execution.
 *
 * Mutable execution supports events' addition.
 * Events can only be added according to the causal order.
 * That is, whenever a new event is added to the execution,
 * all the events on which it depends, including its program order predecessors,
 * should already be added into the execution.
 *
 * @param E the type of events stored in the execution, must extend `ThreadEvent`.
 *
 * @see Execution
 * @see ExecutionFrontier
 */
interface MutableExecution<E: ThreadEvent> : Execution<E> {

    /**
     * Adds the specified event to the mutable execution.
     * All the causal predecessors of the event must already be added to the execution.
     *
     * @param event the event to be added to the execution.
     */
    fun add(event: E)

    // TODO: support single (causally-last) event removal (?)
}

val Execution<*>.threadIDs: Set<ThreadId>
    get() = threadMap.keys

val Execution<*>.maxThreadID: ThreadId
    get() = threadIDs.maxOrNull() ?: -1

fun Execution<*>.getThreadSize(tid: ThreadId): Int =
    get(tid)?.size ?: 0

fun Execution<*>.lastPosition(tid: ThreadId): Int =
    getThreadSize(tid) - 1

fun<E : ThreadEvent> Execution<E>.firstEvent(tid: ThreadId): E? =
    get(tid)?.firstOrNull()

fun<E : ThreadEvent> Execution<E>.lastEvent(tid: ThreadId): E? =
    get(tid)?.lastOrNull()

operator fun<E : ThreadEvent> Execution<E>.get(tid: ThreadId, pos: Int): E? =
    get(tid)?.getOrNull(pos)

fun<E : ThreadEvent> Execution<E>.nextEvent(event: E): E? =
    get(event.threadId)?.let { events ->
        require(events[event.threadPosition] == event)
        events.getOrNull(event.threadPosition + 1)
    }

// TODO: make default constructor
fun<E : ThreadEvent> Execution(): Execution<E> =
    MutableExecution()

// TODO: not sure if this code works as intended
fun<E : ThreadEvent> MutableExecution(): MutableExecution<E> =
    ExecutionImpl(mutableMapOf<ThreadId, SortedMutableList<E>>().withDefault{ _ -> sortedArrayListOf() })

fun<E : ThreadEvent> executionOf(vararg pairs: Pair<ThreadId, List<E>>): Execution<E> =
    mutableExecutionOf(*pairs)

fun<E : ThreadEvent> mutableExecutionOf(vararg pairs: Pair<ThreadId, List<E>>): MutableExecution<E> =
    ExecutionImpl(pairs.associate { (tid, events) -> (tid to SortedArrayList(events)) }.withDefault{ _ -> sortedArrayListOf() })

private class ExecutionImpl<E : ThreadEvent>(
    override val threadMap: ThreadMap<SortedMutableList<E>>
) : MutableExecution<E> {

    override var size: Int = threadMap.values.sumOf { it.size }
        private set

    override fun isEmpty(): Boolean =
        (size == 0)

    override fun get(tid: ThreadId): SortedMutableList<E>? =
        threadMap[tid]

    override fun add(event: E) {
        ++size
        threadMap[event.threadId]!!
            .ensure { event.parent == it.lastOrNull() }
            .also { it.add(event) }
    }

    override fun equals(other: Any?): Boolean =
        (other is ExecutionImpl<*>) && (size == other.size) && (threadMap == other.threadMap)

    override fun hashCode(): Int =
       threadMap.hashCode()

    override fun toString(): String = buildString {
        appendLine("<======== Execution Graph @${hashCode()} ========>")
        threadIDs.toList().sorted().forEach { tid ->
            val events = threadMap[tid] ?: return@forEach
            appendLine("[-------- Thread #${tid} --------]")
            for (event in events) {
                appendLine("$event")
                if (event.dependencies.isNotEmpty()) {
                    appendLine("    dependencies: ${event.dependencies.joinToString()}}")
                }
            }
        }
    }

}

fun<E : ThreadEvent> Execution<E>.toFrontier(): ExecutionFrontier<E> =
    toMutableFrontier()

fun<E : ThreadEvent> Execution<E>.toMutableFrontier(): MutableExecutionFrontier<E> =
    threadIDs.map { tid ->
        tid to get(tid)?.lastOrNull()
    }.let {
        mutableExecutionFrontierOf(*it.toTypedArray())
    }

fun<E : ThreadEvent> Execution<E>.calculateFrontier(clock: VectorClock): MutableExecutionFrontier<E> =
    (0 until maxThreadID).mapNotNull { tid ->
        val timestamp = clock[tid]
        if (timestamp >= 0)
            tid to this[tid, timestamp]
        else null
    }.let {
        mutableExecutionFrontierOf(*it.toTypedArray())
    }

fun<E : ThreadEvent> Execution<E>.buildEnumerator() = object : Enumerator<E> {

    private val events = enumerationOrderSorted()

    private val eventIndices = threadMap.mapValues { (_, threadEvents) ->
        List(threadEvents.size) { pos ->
            events.indexOf(threadEvents[pos]).ensure { it >= 0 }
        }
    }

    override fun get(i: Int): E {
        return events[i]
    }

    override fun get(x: E): Int {
        return eventIndices[x.threadId]!![x.threadPosition]
    }

}

fun Execution<*>.locations(): Set<MemoryLocation> {
    val locations = mutableSetOf<MemoryLocation>()
    for (event in this) {
        val location = (event.label as? MemoryAccessLabel)?.location
            ?: continue
        locations.add(location)
    }
    return locations
}

fun Execution<AtomicThreadEvent>.getResponse(request: AtomicThreadEvent): AtomicThreadEvent? {
    // TODO: handle the case of span-start label
    require(request.label.isRequest && !request.label.isSpanLabel)
    return this[request.threadId, request.threadPosition + 1]?.ensure {
        it.isValidResponse(request)
    }
}

fun<E : ThreadEvent> Execution<E>.isBlockedDanglingRequest(event: E): Boolean =
    event.label.isRequest &&
    event.label.isBlocking &&
    event == this[event.threadId]?.last()

/**
 * Computes the backward vector clock for the given event and relation.
 *
 * Backward vector clock encodes the position of the last event in each thread,
 * on which the given event depends on according to the given relation.
 *
 * Graphically, this can be illustrated by the following picture:
 *     e1  e2  e3
 *      \  |  /
 *         e
 *
 * Formally, for an event `e` and relation `r` the backward vector clock stores
 * a mapping `tid -> pos` such that `e' = execution[tid, pos]` is
 * the last event in thread `tid` such that `(e', e) \in r`.
 *
 * This function has time complexity O(E^2) where E is the number of events in execution.
 * If the given relation respects program order (see definition below),
 * then the time complexity can be optimized (using binary search)
 * to O(E * T * log E) where T is the number of threads.
 *
 * The relation is said to respect the program order (in the backward direction) if the following is true:
 *   (x, y) \in r and (z, x) \in po implies (z, y) \in r
 *
 *
 * @param event The event for which to compute the backward vector clock.
 * @param relation The relation used to determine the causality between events.
 * @return The computed backward vector clock.
 */
fun<E : ThreadEvent> Execution<E>.computeBackwardVectorClock(event: E, relation: Relation<E>,
    respectsProgramOrder: Boolean = false
): VectorClock {
    val capacity = 1 + this.maxThreadID
    val clock = MutableVectorClock(capacity)
    for (i in 0 until capacity) {
        val threadEvents = get(i) ?: continue
        val position = if (respectsProgramOrder) {
            threadEvents.binarySearch { !relation(it, event) }
        } else {
            threadEvents.indexOfFirst { !relation(it, event) }
        }
        clock[i] = (position - 1).coerceAtLeast(-1)
    }
    return clock
}

/**
 * Computes the forward vector clock for the given event and relation.
 *
 * Forward vector clock encodes the position of the first event in each thread,
 * that depends on given event according to the given relation.
 *
 * Graphically, this can be illustrated by the following picture:
 *          e
 *       /  |  \
 *     e1  e2  e3
 *
 * Formally, for an event `e` and relation `r` the forward vector clock stores
 * a mapping `tid -> pos` such that `e' = execution[tid, pos]` is
 * the first event in thread `tid` such that `(e, e') \in r`.
 *
 * This function has time complexity O(E^2) where E is the number of events in execution.
 * If the given relation respects program order (see definition below),
 * then the time complexity can be optimized (using binary search)
 * to O(E * T * log E) where T is the number of threads.
 *
 * The relation is said to respect the program order (in the forward direction) if the following is true:
 *   (x, y) \in r and (y, z) \in po implies (x, z) \in r
 *
 *
 * @param event The event for which to compute the forward vector clock.
 * @param relation The relation used to determine the causality between events.
 * @return The computed forward vector clock.
 */
fun<E : ThreadEvent> Execution<E>.computeForwardVectorClock(event: E, relation: Relation<E>,
    respectsProgramOrder: Boolean = false,
): VectorClock {
    val capacity = 1 + this.maxThreadID
    val clock = MutableVectorClock(capacity)
    for (i in 0 until capacity) {
        val threadEvents = get(i) ?: continue
        val position = if (respectsProgramOrder) {
            threadEvents.binarySearch { relation(event, it) }
        } else {
            threadEvents.indexOfFirst { relation(event, it) }
        }
        clock[i] = position
    }
    return clock
}

fun VectorClock.observes(event: ThreadEvent): Boolean =
    observes(event.threadId, event.threadPosition)

fun VectorClock.observes(execution: Execution<*>): Boolean =
    execution.threadMap.values.all { events ->
        events.lastOrNull()?.let { observes(it) } ?: true
    }

fun<E : ThreadEvent> Covering<E>.coverable(event: E, clock: VectorClock): Boolean =
    this(event).all { clock.observes(it) }

fun<E : ThreadEvent> Covering<E>.allCoverable(events: List<E>, clock: VectorClock): Boolean =
    events.all { event -> this(event).all { clock.observes(it) || it in events } }

fun<E : ThreadEvent> Covering<E>.firstCoverable(events: List<E>, clock: VectorClock): Boolean =
    coverable(events.first(), clock)

fun <E : Event> Collection<E>.enumerationOrderSorted(): List<E> =
    this.sorted()

fun<E : ThreadEvent> Execution<E>.enumerationOrderSorted(): List<E> =
    this.sorted()


fun<E : ThreadEvent> Execution<E>.buildGraph(
    relation: Relation<E>,
    respectsProgramOrder: Boolean = false,
) = object : Graph<E> {
    private val execution = this@buildGraph

    override val nodes: Collection<E>
        get() = execution

    private val enumerator = execution.buildEnumerator()

    private val nThreads = 1 + execution.maxThreadID

    private val adjacencyList = Array(nodes.size) { i ->
        val event = enumerator[i]
        val clock = execution.computeForwardVectorClock(event, relation,
            respectsProgramOrder = respectsProgramOrder
        )
        (0 until nThreads).mapNotNull { tid ->
            if (clock[tid] != -1) execution[tid, clock[tid]] else null
        }
    }

    override fun adjacent(node: E): List<E> {
        val idx = enumerator[node]
        return adjacencyList[idx]
    }

}

// TODO: include parent event in covering (?) and remove `External`
fun<E : ThreadEvent> Execution<E>.buildExternalCovering(
    relation: Relation<E>,
    respectsProgramOrder: Boolean = false,
) = object : Covering<E> {

    // TODO: document this precondition!
    init {
        // require(respectsProgramOrder)
    }

    private val execution = this@buildExternalCovering
    private val enumerator = execution.buildEnumerator()

    private val nThreads = 1 + maxThreadID

    val covering: List<List<E>> = execution.indices.map { index ->
        val event = enumerator[index]
        val clock = execution.computeBackwardVectorClock(event, relation,
            respectsProgramOrder = respectsProgramOrder
        )
        (0 until nThreads).mapNotNull { tid ->
            if (tid != event.threadId && clock[tid] != -1)
                execution[tid, clock[tid]]
            else null
        }
    }

    override fun invoke(x: E): List<E> =
        covering[enumerator[x]]

}