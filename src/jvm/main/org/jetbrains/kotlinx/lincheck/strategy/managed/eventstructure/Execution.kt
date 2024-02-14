/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.utils.*


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
    operator fun get(tid: ThreadID): SortedList<E>? =
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

val Execution<*>.threadIDs: Set<ThreadID>
    get() = threadMap.keys

val Execution<*>.maxThreadID: ThreadID
    get() = threadIDs.maxOrNull() ?: -1

fun Execution<*>.getThreadSize(tid: ThreadID): Int =
    get(tid)?.size ?: 0

fun Execution<*>.lastPosition(tid: ThreadID): Int =
    getThreadSize(tid) - 1

fun<E : ThreadEvent> Execution<E>.firstEvent(tid: ThreadID): E? =
    get(tid)?.firstOrNull()

fun<E : ThreadEvent> Execution<E>.lastEvent(tid: ThreadID): E? =
    get(tid)?.lastOrNull()

operator fun<E : ThreadEvent> Execution<E>.get(tid: ThreadID, pos: Int): E? =
    get(tid)?.getOrNull(pos)

fun<E : ThreadEvent> Execution<E>.nextEvent(event: E): E? =
    get(event.threadId)?.let { events ->
        require(events[event.threadPosition] == event)
        events.getOrNull(event.threadPosition + 1)
    }

// TODO: make default constructor
fun<E : ThreadEvent> Execution(nThreads: Int): Execution<E> =
    MutableExecution(nThreads)

fun<E : ThreadEvent> MutableExecution(nThreads: Int): MutableExecution<E> =
    ExecutionImpl(ArrayIntMap(*(0 until nThreads)
        .map { (it to sortedArrayListOf<E>()) }
        .toTypedArray()
    ))

fun<E : ThreadEvent> executionOf(vararg pairs: Pair<ThreadID, List<E>>): Execution<E> =
    mutableExecutionOf(*pairs)

fun<E : ThreadEvent> mutableExecutionOf(vararg pairs: Pair<ThreadID, List<E>>): MutableExecution<E> =
    ExecutionImpl(ArrayIntMap(*pairs
        .map { (tid, events) -> (tid to SortedArrayList(events)) }
        .toTypedArray()
    ))

private class ExecutionImpl<E : ThreadEvent>(
    override val threadMap: ArrayIntMap<SortedMutableList<E>>
) : MutableExecution<E> {

    override var size: Int = threadMap.values.sumOf { it.size }
        private set

    override fun isEmpty(): Boolean =
        (size > 0)

    override fun get(tid: ThreadID): SortedMutableList<E>? =
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

fun<E : ThreadEvent> Execution<E>.isBlockedDanglingRequest(event: E): Boolean {
    return event.label.isRequest && event.label.isBlocking &&
            (event == this[event.threadId]?.last())
}

fun<E : ThreadEvent> Execution<E>.computeVectorClock(event: E, relation: Relation<E>): VectorClock {
    check(this is ExecutionImpl<E>)
    val clock = MutableVectorClock(threadMap.capacity)
    for (i in 0 until threadMap.capacity) {
        val threadEvents = get(i) ?: continue
        clock[i] = (threadEvents.binarySearch { !relation(it, event) } - 1)
            .coerceAtLeast(-1)
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


// TODO: include parent event in covering (?) and remove `External`
fun<E : ThreadEvent> Execution<E>.buildExternalCovering(relation: Relation<E>) = object : Covering<E> {

    // TODO: document this precondition!
    init {
        // require(respectsProgramOrder)
    }

    private val execution = this@buildExternalCovering
    private val indexer = execution.buildEnumerator()

    private val nThreads = 1 + maxThreadID

    val covering: List<List<E>> = execution.indices.map { index ->
        val event = indexer[index]
        val clock = execution.computeVectorClock(event, relation)
        (0 until nThreads).mapNotNull { tid ->
            if (tid != event.threadId && clock[tid] != -1)
                execution[tid, clock[tid]]
            else null
        }
    }

    override fun invoke(x: E): List<E> =
        covering[indexer[x]]

}