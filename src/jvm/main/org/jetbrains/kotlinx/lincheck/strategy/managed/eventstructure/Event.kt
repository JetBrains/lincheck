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

    fun predNth(n: Int): ThreadEvent?
}

fun ThreadEvent.pred(inclusive: Boolean = false, predicate: (Event) -> Boolean): Event? {
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

fun ThreadEvent.threadPrefix(inclusive: Boolean = false, reversed: Boolean = false): List<ThreadEvent> {
    val events = arrayListOf<ThreadEvent>()
    if (inclusive) {
        events.add(this)
    }
    // obtain a list of predecessors of given event
    var event: ThreadEvent? = parent
    while (event != null) {
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

interface SynchronizedEvent : Event {
    /**
     * List of events which synchronize into the given event.
     */
    val synchronized: List<Event>
}

fun SynchronizedEvent.resynchronize(algebra: SynchronizationAlgebra): EventLabel? {
    if (synchronized.isEmpty())
        return label
    return synchronized.fold (null) { label: EventLabel?, event ->
        label?.let { algebra.synchronize(it, event.label) }
    }
}

abstract class AbstractEvent(override val label: EventLabel) : Event {

    companion object {
        private var nextID: EventID = 0
    }

    override val id: EventID = nextID++

    protected open fun validate() {}

}

abstract class AbstractThreadEvent(
    label: EventLabel,
    final override val threadId: Int,
    final override val parent: ThreadEvent? = null,
) : AbstractEvent(label), ThreadEvent {

    final override val threadPosition: Int =
        1 + (parent?.threadPosition ?: -1)

    override fun validate() {
        super.validate()
        check((parent != null) implies (parent in dependencies))
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

    companion object {
        private const val N_JUMPS = 10
        private const val MAX_JUMP = 1 shl (N_JUMPS - 1)

        private fun calculateJumps(event: AbstractThreadEvent) {
            require(N_JUMPS > 0)
            event.jumps[0] = (event.parent as AbstractThreadEvent)
            for (i in 1 until N_JUMPS) {
                event.jumps[i] = event.jumps[i - 1]?.jumps?.get(i - 1)
            }
        }
    }

}

class AtomicThreadEvent(
    label: EventLabel,
    threadId: Int,
    parent: ThreadEvent? = null,
    /**
     * Sender events corresponding to this event.
     * Applicable only to response events.
     */
    val senders: List<Event> = listOf(),
    /**
     * The allocation event for the accessed object.
     * Applicable only to object accessing events.
     */
    val allocation: Event? = null,
) : AbstractThreadEvent(
    label = label,
    threadId = threadId,
    parent = parent,
), SynchronizedEvent {

    /**
     * Request event corresponding to this event.
     * Applicable only to response and receive events.
     */
    val request: Event? =
        parent?.takeIf { label.isResponse }

    override val dependencies: List<Event> =
        listOfNotNull(parent, allocation) + senders

    override val synchronized: List<Event> =
        if (label.isResponse) (listOf(request!!) + senders) else listOf()

    init {
        validate()
    }

    override fun validate() {
        super.validate()
        check(label.isResponse implies (request != null && senders.isNotEmpty()))
        check(!label.isResponse implies (request == null && senders.isEmpty()))
        check((label.obj != null) implies (allocation != null))
    }

}

class OldEvent private constructor(
    val id: EventID,
    /**
     * Event's label.
     */
    val label: EventLabel,
    /**
     * Event's thread
     */
    val threadId: Int,
    /**
     * Event's position in a thread
     * (i.e. number of its program-order predecessors).
     */
    val threadPosition: Int = 0,
    /**
     * Event's parent in program order.
     */
    val parent: Event? = null,
    /**
     * List of event's dependencies
     * (e.g. reads-from write for a read event).
     */
    val dependencies: List<Event> = listOf(),
    /**
     * Vector clock to track causality relation.
     */
    val causalityClock: VectorClock,
    /**
     * State of the execution frontier at the point when event is created.
     */
    val frontier: ExecutionFrontier,
    /**
     * Frontier of pinned events.
     * Pinned events are the events that should not be
     * considering for branching in an exploration starting from this event.
     */
    val pinnedEvents: ExecutionFrontier,
) : Comparable<Event> {

    var visited: Boolean = false
        private set

    // should only be called from EventStructure
    // TODO: enforce this invariant!
    fun visit() {
        visited = true
    }

    companion object {
        private var nextId: EventID = 0

        fun create(
            threadId: Int,
            threadPosition: Int,
            label: EventLabel,
            parent: Event?,
            dependencies: List<Event>,
            causalityClock: VectorClock,
            // TODO: make read-only here
            frontier: MutableExecutionFrontier,
            // TODO: make read-only here
            pinnedEvents: MutableExecutionFrontier,
        ): Event {
            val id = nextId++
            return Event(
                id,
                threadId = threadId,
                threadPosition = threadPosition,
                label = label,
                parent = parent,
                dependencies = dependencies,
                causalityClock = causalityClock,
                frontier = frontier,
                pinnedEvents = pinnedEvents,
            ).apply {
                calculateJumps(this)
                pinnedEvents[threadId] = this
            }
        }

        private const val N_JUMPS = 10
        private const val MAX_JUMP = 1 shl (N_JUMPS - 1)

        private fun calculateJumps(event: Event) {
            require(N_JUMPS > 0)
            event.jumps[0] = event.parent
            for (i in 1 until N_JUMPS) {
                event.jumps[i] = event.jumps[i - 1]?.jumps?.get(i - 1)
            }
        }

    }

    val syncFrom: Event by lazy {
        require(label.isResponse)
        dependencies.first { label.isValidResponse(it.label) }
    }

    val readsFrom: Event
        get() = run {
            require(label is ReadAccessLabel)
            syncFrom
        }

    val exclusiveReadPart: Event by lazy {
        require(label is WriteAccessLabel && label.isExclusive)
        check(parent != null)
        parent.ensure {
            it.label is ReadAccessLabel && it.label.isResponse
                && it.label.location == label.location
                && it.label.isExclusive
        }
    }

    val locksFrom: Event
        get() = run {
            require(label is LockLabel)
            syncFrom
        }

    val notifiedBy: Event
        get() = run {
            require(label is WaitLabel)
            syncFrom
        }

    val allocatedBy: Event by lazy {
        // TODO: generalize?
        require(label is MemoryAccessLabel || label is MutexLabel)
        // TODO: unify cases
        val obj = when (label) {
            is MemoryAccessLabel -> label.location.obj.opaque()
            is MutexLabel -> label.mutex
            else -> unreachable()
        }
        dependencies.first { it.label.asObjectAllocationLabel(obj) != null }
    }

    /**
     * Checks whether this event is valid response to the [request] event.
     * If this event is not a response or [request] is not a request returns false.
     *
     * Response is considered to be valid if:
     * - request is a parent of response,
     * - request-label can be synchronized-into response-label.
     *
     * @see EventLabel.synchronizesInto
     */
    fun isValidResponse(request: Event) =
        request.label.isRequest && label.isResponse && parent == request
                && label.isValidResponse(request.label)

    /**
     * Checks whether this event is valid response to its parent request event.
     * If this event is not a response or its parent is not a request returns false.
     *
     * @see isValidResponse
     */
    fun isValidResponse() =
        parent != null && isValidResponse(parent)

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
    fun isWritePartOfAtomicUpdate(readResponse: Event) =
        readResponse.label is ReadAccessLabel && readResponse.label.isResponse && readResponse.label.isExclusive &&
        label is WriteAccessLabel && label.isExclusive && parent == readResponse &&
        label.location == readResponse.label.location

    override fun equals(other: Any?): Boolean {
        return (other is Event) && (id == other.id)
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun compareTo(other: Event): Int {
        return id.compareTo(other.id)
    }

    override fun toString(): String {
        return "#${id}: [${threadId}, ${threadPosition}] $label"
    }
}

val programOrder: PartialOrder<Event> = PartialOrder.ofLessThan { x, y ->
    if (x.threadId != y.threadId || x.threadPosition >= y.threadPosition)
        false
    else (x == y.predNth(y.threadPosition - x.threadPosition))
}

val causalityOrder: PartialOrder<Event> = PartialOrder.ofLessOrEqual { x, y ->
    y.causalityClock.observes(x.threadId, x.threadPosition)
}

val externalCausalityCovering: Covering<Event> = Covering { y ->
    y.dependencies
}