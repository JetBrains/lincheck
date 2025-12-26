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

import org.jetbrains.kotlinx.lincheck.strategy.managed.MemoryLocation
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.ensureNotNull

typealias EventID = Int

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
     * List of event's dependencies.
     */
    override val dependencies: List<ThreadEvent>

    /**
     * Vector clock to track causality relation.
     */
    val causalityClock: VectorClock

    /**
     * Returns n-th predecessor of the given event.
     */
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

fun ThreadEvent.threadPrefix(inclusive: Boolean = false, reversed: Boolean = false): List<ThreadEvent> {
    val events = arrayListOf<ThreadEvent>()
    if (inclusive) {
        events.add(this)
    }
    // obtain a list of predecessors of a given event
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

private fun ThreadEvent?.calculateNextEventPosition(): Int =
    1 + (this?.threadPosition ?: -1)


interface SynchronizedEvent : Event {
    /**
     * List of events which synchronize into the given event.
     */
    val synchronized: List<Event>
}

fun SynchronizedEvent.resynchronize(algebra: SynchronizationAlgebra): EventLabel =
    if (synchronized.isNotEmpty())
        algebra.synchronize(synchronized).ensureNotNull()
    else label

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
    if (label.isResponse && !label.isSpanLabel) parent!! else null

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
    require(label.satisfies<WriteAccessLabel> { isExclusive })
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
    label.isResponse && request.label.isRequest && parent == request && label.isValidResponse(request.label)

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
fun AtomicThreadEvent.isWritePartOfAtomicUpdate(readResponse: ThreadEvent): Boolean {
    val writeLabel = label.refine<WriteAccessLabel> { isExclusive }
        ?: return false
    val readLabel = readResponse.label.refine<ReadAccessLabel> { isResponse && isExclusive }
        ?: return false
    return (parent == readResponse) && readLabel.location == writeLabel.location
}


/**
 * Hyper event is a composite event consisting of multiple atomic events.
 * It allows viewing subset of events of an execution as an atomic event by itself.
 * Some notable examples of hyper events are listed below:
 * - pair of consecutive request and response events of the same operation
 *   can be viewed as a composite receive event;
 * - pair of exclusive read and write events of the same atomic operation
 *   can be viewed as a composite read-modify-write event;
 * - all the events between lock acquire and lock release events
 *   can be viewed as a composite critical section event;
 * for other examples see subclasses of this class.
 *
 * We support only sequential hyper events --- that is set of events
 * totally ordered by some criterion.
 *
 * This class of events is called "hyper" after term "hyper pomsets" from [1].
 *
 * [1] Brunet, Paul, and David Pym.
 *    "Pomsets with Boxes: Protection, Separation, and Locality in Concurrent Kleene Algebra."
 *    5th International Conference on Formal Structures for Computation and Deduction. 2020.
 *
 */
interface HyperEvent : Event {
    val events: List<AtomicThreadEvent>
}

abstract class AbstractEvent(final override val label: EventLabel) : Event {

    companion object {
        private var nextID: EventID = 0
    }

    final override val id: EventID = nextID++

    protected open fun validate() {}

    override fun equals(other: Any?): Boolean {
        // TODO: think again --- is it sound? seems so, as we only create single event per ID
        return (this === other)
        // return (other is AbstractEvent) && (id == other.id)
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

}

abstract class AbstractThreadEvent(
    label: EventLabel,
    parent: AbstractThreadEvent?,
    dependencies: List<ThreadEvent>,
) : AbstractEvent(label), ThreadEvent {

    final override val threadId: Int = when (label) {
        is InitializationLabel -> label.initThreadID
        is ThreadStartLabel -> label.threadId
        is ActorLabel -> label.threadId
        else -> parent!!.threadId
    }

    final override val threadPosition: Int =
        parent.calculateNextEventPosition()

    final override val causalityClock: VectorClock = run {
        dependencies.fold(parent?.causalityClock?.copy() ?: MutableVectorClock()) { clock, event ->
            clock + event.causalityClock
        }.apply {
            set(threadId, threadPosition)
        }
    }

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

    init {
        calculateJumps(jumps, parent)
    }

    companion object {
        private const val N_JUMPS = 10
        private const val MAX_JUMP = 1 shl (N_JUMPS - 1)

        private fun calculateJumps(jumps: Array<AbstractThreadEvent?>, parent: AbstractThreadEvent?) {
            require(N_JUMPS > 0)
            require(jumps.size >= N_JUMPS)
            jumps[0] = parent
            for (i in 1 until N_JUMPS) {
                jumps[i] = jumps[i - 1]?.jumps?.get(i - 1)
            }
        }
    }

}

class AtomicThreadEventImpl(
    label: EventLabel,
    override val parent: AtomicThreadEvent?,
    /**
     * Sender events corresponding to this event.
     * Applicable only to response events.
     */
    override val senders: List<AtomicThreadEvent> = listOf(),
    /**
     * The allocation event for the accessed object.
     * Applicable only to object accessing events.
     */
    override val allocation: AtomicThreadEvent? = null,
    /**
     * The allocation event for the value produced by this label
     * (for example, written value for write access label).
     */
    // TODO: refactor!
    override val source: AtomicThreadEvent? = null,
    /**
     * List of event's dependencies
     */
    override val dependencies: List<AtomicThreadEvent> = listOf(),
) : AtomicThreadEvent, AbstractThreadEvent(label, (parent as AbstractThreadEvent?), dependencies) {

    final override val synchronized: List<ThreadEvent> =
        if (label.isResponse && !label.isSpanLabel) (listOf(request!!) + senders) else listOf()

    override fun validate() {
        super.validate()
        // constraints for atomic non-span-related events
        if (!label.isSpanLabel) {
            // the request event should not follow another request event
            // because the earlier request should first receive its response
            require((label.isRequest && parent != null) implies {
                !parent!!.label.isRequest || parent!!.label.isSpanLabel
            })
            // only the response event should have a corresponding request part
            require(label.isResponse equivalent (request != null))
            // response and receive events (and only them) should have a corresponding list
            // of sender events, with which they synchronize-with
            require((label.isResponse || label.isReceive) equivalent senders.isNotEmpty())
        }
        // read-exclusive label should precede every write-exclusive label
        if (label is WriteAccessLabel && label.isExclusive) {
            require(parent != null)
            require(parent!!.label.satisfies<ReadAccessLabel> {
                isResponse && isExclusive && location == label.location
            })
        }
    }

}

// TODO: rename to SpanEvent
class HyperThreadEvent(
    label: EventLabel,
    override val parent: HyperThreadEvent?,
    override val dependencies: List<HyperThreadEvent>,
    override val events: List<AtomicThreadEvent>,
) : HyperEvent, AbstractThreadEvent(label, parent, dependencies)


val programOrder = Relation<ThreadEvent> { x, y ->
    if (x.threadId != y.threadId || x.threadPosition >= y.threadPosition)
        false
    else (x == y.predNth(y.threadPosition - x.threadPosition))
}

val causalityOrder = Relation<ThreadEvent> { x, y ->
    (x != y) && y.causalityClock.observes(x.threadId, x.threadPosition)
}

val causalityCovering: Covering<ThreadEvent> = Covering { it.dependencies }

fun getLocationForSameLocationAccesses(x: Event, y: Event): MemoryLocation? {
    val xloc = (x.label as? MemoryAccessLabel)?.location
    val yloc = (y.label as? MemoryAccessLabel)?.location
    val isSameLocation = when {
        xloc != null && yloc != null -> xloc == yloc
        xloc != null -> y.label.isMemoryAccessTo(xloc)
        yloc != null -> x.label.isMemoryAccessTo(yloc)
        else -> false
    }
    return if (isSameLocation) (xloc ?: yloc) else null
}

fun getLocationForSameLocationWriteAccesses(x: Event, y: Event): MemoryLocation? {
    val xloc = (x.label as? WriteAccessLabel)?.location
    val yloc = (y.label as? WriteAccessLabel)?.location
    val isSameLocation = when {
        xloc != null && yloc != null -> xloc == yloc
        xloc != null -> y.label.isWriteAccessTo(xloc)
        yloc != null -> x.label.isWriteAccessTo(yloc)
        else -> false
    }
    return if (isSameLocation) (xloc ?: yloc) else null
}

fun List<Event>.getLocationForSameLocationMemoryAccesses(): MemoryLocation? {
    val location = this.findMapped { (it.label as? MemoryAccessLabel)?.location }
        ?: return null
    return if (all { it.label.isWriteAccessTo(location) })
        location
    else null
}

fun List<Event>.getLocationForSameLocationWriteAccesses(): MemoryLocation? {
    val location = this.findMapped { (it.label as? WriteAccessLabel)?.location }
        ?: return null
    return if (all { it.label.isWriteAccessTo(location) })
        location
    else null
}