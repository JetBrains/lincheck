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

import  org.jetbrains.kotlinx.lincheck.ensure
import org.jetbrains.kotlinx.lincheck.ensureNotNull
import org.jetbrains.kotlinx.lincheck.implies
import org.jetbrains.kotlinx.lincheck.strategy.managed.opaque
import org.jetbrains.kotlinx.lincheck.unreachable
import org.jetbrains.kotlinx.lincheck.utils.IntMap
import org.jetbrains.kotlinx.lincheck.utils.MutableIntMap

typealias EventID = Int

typealias ThreadID = Int

typealias ThreadMap<T> = IntMap<T>

typealias MutableThreadMap<T> = MutableIntMap<T>

interface Event : Comparable<Event> {
    /**
     * Event's ID.
     */
    val id: EventID

    /**
     * Event's label.
     */
    val label: EventLabel

    /**
     * List of event's dependencies.
     */
    val dependencies: List<Event>

    override fun compareTo(other: Event): Int {
        return id.compareTo(other.id)
    }
}

interface ThreadEvent : Event {
    /**
     * Event's thread
     */
    val threadId: Int

    /**
     * Event's position in a thread.
     */
    val threadPosition: Int

    /**
     * Event's parent in program order.
     */
    // TODO: do we need to store it in `ThreadEvent` ?
    val parent: ThreadEvent?

    /**
     * Vector clock to track causality relation.
     */
    val causalityClock: VectorClock

    fun predNth(n: Int): ThreadEvent?
}

fun ThreadEvent.pred(inclusive: Boolean = false, predicate: (ThreadEvent) -> Boolean): ThreadEvent? {
    if (inclusive && predicate(this))
        return this
    var event: ThreadEvent? = parent
    while (event != null && !predicate(event)) {
        event = event.parent
    }
    return event
}

val ThreadEvent.threadRoot: Event
    get() = predNth(threadPosition)!!

inline fun<reified E : ThreadEvent> ThreadEvent.threadPrefix(
    inclusive: Boolean = false,
    reversed: Boolean = false
): List<E> {
    if (this !is E) {
        return listOf()
    }
    val events = arrayListOf<E>()
    if (inclusive) {
        events.add(this)
    }
    // obtain a list of predecessors of given event
    var event: ThreadEvent? = parent
    while (event != null) {
        if (event !is E) {
            return listOf()
        }
        events.add(event)
        event = event.parent
    }
    // since we iterate from child to parent, the list by default is in reverse order;
    // if the callee passed `reversed=true` leave it as is, otherwise reverse
    // to get the list in the order from ancestors to descendants
    if (!reversed) {
        events.reverse()
    }
    return events
}

private fun ThreadEvent?.calculateNextEventPosition(): Int =
    1 + (this?.threadPosition ?: -1)


interface SynchronizedEvent : Event {
    /**
     * List of events which synchronize into the given event.
     */
    val synchronized: List<Event>
}

fun SynchronizedEvent.resynchronize(algebra: SynchronizationAlgebra): EventLabel {
    if (synchronized.isEmpty())
        return label
    return synchronized.fold (null) { label: EventLabel?, event ->
        algebra.synchronize(label, event.label)
    }.ensureNotNull()
}

interface AtomicThreadEvent : ThreadEvent, SynchronizedEvent {

    override val parent: AtomicThreadEvent?

    /**
     * Sender events corresponding to this event.
     * Applicable only to response events.
     */
    val senders: List<AtomicThreadEvent>

    /**
     * The allocation event for the accessed object.
     * Applicable only to object accessing events.
     */
    val allocation: AtomicThreadEvent?

    /**
     * The allocation event for the value produced by this label
     * (for example, written value for write access label).
     */
    // TODO: refactor!
    val source: AtomicThreadEvent?
}

/**
 * Request event corresponding to this event.
 * Applicable only to response and receive events.
 */
val AtomicThreadEvent.request: AtomicThreadEvent? get() =
    if (label.isResponse) parent!! else null

val AtomicThreadEvent.syncFrom: AtomicThreadEvent get() = run {
    require(label.isResponse)
    require(senders.size == 1)
    senders.first()
}

val AtomicThreadEvent.readsFrom: AtomicThreadEvent get() = run {
    require(label is ReadAccessLabel)
    syncFrom
}

val AtomicThreadEvent.locksFrom: AtomicThreadEvent get() = run {
    require(label is LockLabel)
    syncFrom
}

val AtomicThreadEvent.notifiedBy: AtomicThreadEvent get() = run {
    require(label is WaitLabel)
    syncFrom
}

val AtomicThreadEvent.exclusiveReadPart: AtomicThreadEvent get() = run {
    require(label is WriteAccessLabel && (label as WriteAccessLabel).isExclusive)
    parent!!
}

/**
 * Checks whether this event is valid response to the [request] event.
 * If this event is not a response or [request] is not a request returns false.
 *
 * Response is considered to be valid if:
 * - request is a parent of response,
 * - request-label can be synchronized-into response-label.
 *
 * @see EventLabel.isValidResponse
 */
fun AtomicThreadEvent.isValidResponse(request: ThreadEvent) =
    request.label.isRequest && label.isResponse
            && parent == request
            && label.isValidResponse(request.label)

/**
 * Checks whether this event is a valid response to its parent request event.
 * If this event is not a response or its parent is not a request returns false.
 *
 * @see isValidResponse
 */
fun AtomicThreadEvent.isValidResponse() =
    parent?.let { isValidResponse(it) } ?: false

/**
 * Checks whether this event is valid write part of atomic read-modify-write,
 * of which the [readResponse] is a read-response part.
 * If this event is not an exclusive write or [readResponse] is not an exclusive read-response returns false.
 *
 * Write is considered to be valid write part of read-modify-write if:
 * - read-response is a parent of write,
 * - read-response and write access same location,
 * - both have exclusive flag set.
 * request-label can be synchronized-into response-label.
 *
 * @see MemoryAccessLabel.isExclusive
 */
fun AtomicThreadEvent.isWritePartOfAtomicUpdate(readResponse: ThreadEvent) =
    label is WriteAccessLabel && (label as WriteAccessLabel).isExclusive
            && parent == readResponse && readResponse.label.let {
                it is ReadAccessLabel && it.isResponse && it.isExclusive
                    && it.location == (label as WriteAccessLabel).location
            }

abstract class AbstractEvent(final override val label: EventLabel) : Event {

    companion object {
        private var nextID: EventID = 0
    }

    final override val id: EventID = nextID++

    protected open fun validate() {}

    override fun equals(other: Any?): Boolean {
        return (other is AbstractEvent) && (id == other.id)
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

}

abstract class AbstractThreadEvent(
    label: EventLabel,
    override val parent: AbstractThreadEvent?,
    final override val threadId: Int,
    final override val threadPosition: Int,
    final override val causalityClock: VectorClock,
) : AbstractEvent(label), ThreadEvent {

    override fun validate() {
        super.validate()
        require(threadPosition == parent.calculateNextEventPosition())
        // require((parent != null) implies { parent!! in dependencies })
    }

    override fun toString(): String {
        return "#${id}: [${threadId}, ${threadPosition}] $label"
    }

    override fun predNth(n: Int): ThreadEvent? {
        return predNthOptimized(n)
            // .also { check(it == predNthNaive(n)) }
    }

    // naive implementation with O(N) complexity, just for testing and debugging
    private fun predNthNaive(n : Int): ThreadEvent? {
        var e: ThreadEvent = this
        for (i in 0 until n)
            e = e.parent ?: return null
        return e
    }

    // binary lifting search with O(lgN) complexity
    // https://cp-algorithms.com/graph/lca_binary_lifting.html;
    private fun predNthOptimized(n: Int): ThreadEvent? {
        require(n >= 0)
        var e = this
        var r = n
        while (r > MAX_JUMP) {
            e = e.jumps[N_JUMPS - 1] ?: return null
            r -= MAX_JUMP
        }
        while (r != 0) {
            val k = 31 - Integer.numberOfLeadingZeros(r)
            val jump = Integer.highestOneBit(r)
            e = e.jumps[k] ?: return null
            r -= jump
        }
        return e
    }

    private val jumps = Array<AbstractThreadEvent?>(N_JUMPS) { null }


    protected open fun initialize() {
        calculateJumps(this)
    }

    companion object {
        private const val N_JUMPS = 10
        private const val MAX_JUMP = 1 shl (N_JUMPS - 1)

        private fun calculateJumps(event: AbstractThreadEvent) {
            require(N_JUMPS > 0)
            event.jumps[0] = event.parent
            for (i in 1 until N_JUMPS) {
                event.jumps[i] = event.jumps[i - 1]?.jumps?.get(i - 1)
            }
        }
    }

}

abstract class AbstractAtomicThreadEvent(
    label: EventLabel,
    threadId: Int,
    override val parent: AbstractAtomicThreadEvent?,
    /**
     * Sender events corresponding to this event.
     * Applicable only to response events.
     */
    final override val senders: List<AtomicThreadEvent> = listOf(),
    /**
     * The allocation event for the accessed object.
     * Applicable only to object accessing events.
     */
    final override val allocation: AtomicThreadEvent? = null,
    /**
     * The allocation event for the value produced by this label
     * (for example, written value for write access label).
     */
    // TODO: refactor!
    final override val source: AtomicThreadEvent? = null,

    causalityClock: VectorClock,
) : AbstractThreadEvent(
    label = label,
    parent = parent,
    threadId = threadId,
    threadPosition = parent.calculateNextEventPosition(),
    causalityClock = causalityClock,
), AtomicThreadEvent {

    final override val dependencies: List<ThreadEvent> =
        listOfNotNull(allocation, source) + senders

    final override val synchronized: List<ThreadEvent> =
        if (label.isResponse) (listOf(request!!) + senders) else listOf()

    override fun validate() {
        super.validate()
        require(label.isResponse implies (request != null && senders.isNotEmpty()))
        require(!label.isResponse implies (request == null && senders.isEmpty()))
        // check that read-exclusive label precedes that write-exclusive label
        if (label is WriteAccessLabel && label.isExclusive) {
            require(parent != null)
            parent!!.label.ensure {
                it is ReadAccessLabel
                    && it.isResponse
                    && it.isExclusive
                    && it.location == label.location
            }
        }
    }

}

val programOrder: PartialOrder<ThreadEvent> = PartialOrder.ofLessThan { x, y ->
    if (x.threadId != y.threadId || x.threadPosition >= y.threadPosition)
        false
    else (x == y.predNth(y.threadPosition - x.threadPosition))
}

val causalityOrder: PartialOrder<ThreadEvent> = PartialOrder.ofLessOrEqual { x, y ->
    y.causalityClock.observes(x.threadId, x.threadPosition)
}