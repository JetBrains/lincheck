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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstruct

import org.jetbrains.kotlinx.lincheck.strategy.managed.MemoryTracker
import org.jetbrains.kotlinx.lincheck.strategy.managed.defaultValueByDescriptor
import kotlin.collections.set

class Event private constructor(
    val id: EventID,
    /**
     * Event's position in a thread
     * (i.e. number of its program-order predecessors).
     */
    val threadPosition: Int = 0,
    /**
     * Event's label.
     */
    val label: EventLabel = EmptyLabel(),
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
    val causalityClock: VectorClock<Int, Event>,
    /**
     * State of the execution frontier at the point when event is created.
     */
    val frontier: ExecutionFrontier,
) : Comparable<Event> {
    val threadId: Int = label.threadId

    var visited: Boolean = false
        private set

    fun predNth(n: Int): Event? {
        var e = this
        // current implementation has O(N) complexity,
        // as an optimization, we can implement binary lifting and get O(lgN) complexity
        // https://cp-algorithms.com/graph/lca_binary_lifting.html;
        // since `predNth` is used to compute programOrder
        // this optimization might be crucial for performance
        for (i in 0 until n)
            e = e.parent ?: return null
        return e
    }

    // should only be called from EventStructure
    // TODO: enforce this invariant!
    fun visit() {
        visited = true
    }

    companion object {
        private var nextId: EventID = 0

        fun create(
            label: EventLabel,
            parent: Event?,
            dependencies: List<Event>,
            frontier: ExecutionFrontier
        ): Event {
            val id = nextId++
            val threadPosition = parent?.let { it.threadPosition + 1 } ?: 0
            val causalityClock = dependencies.fold(parent?.causalityClock?.copy() ?: emptyClock()) { clock, event ->
                clock + event.causalityClock
            }
            return Event(id,
                threadPosition = threadPosition,
                label = label,
                parent = parent,
                dependencies = dependencies,
                causalityClock = causalityClock,
                frontier = frontier
            ).apply {
                causalityClock.update(threadId, this)
                frontier[threadId] = this
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        return (other is Event) && (id == other.id)
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun compareTo(other: Event): Int {
        return id.compareTo(other.id)
    }

    fun getReadsFrom(): Event {
        require(label is MemoryAccessLabel && label.isRead && (label.isResponse || label.isTotal))
        require(dependencies.isNotEmpty())
        return dependencies.first().also {
            // TODO: make `isSynchronized` method to check for labels' compatibility
            //  according to synchronization algebra (e.g. write/read reads-from compatibility)
            check(it.label is MemoryAccessLabel
                && it.label.accessKind == MemoryAccessKind.Write
                && it.label.memId == label.memId
            )
        }
    }

    fun getExclusiveReadPart(): Event {
        require(label is MemoryAccessLabel && label.isWrite && label.isExclusive)
        require(parent != null)
        return parent.also {
            check(it.label is MemoryAccessLabel
                && it.label.isRead && (it.label.isResponse || it.label.isTotal)
                && it.label.memId == label.memId
                && it.label.isExclusive
            )
        }
    }
}

val programOrder: PartialOrder<Event> = PartialOrder.ofLessThan { x, y ->
    if (x.threadId != y.threadId || x.threadPosition >= y.threadPosition)
        false
    else (x == y.predNth(y.threadPosition - x.threadPosition))
}

val causalityOrder: PartialOrder<Event> = PartialOrder.ofLessOrEqual { x, y ->
    y.causalityClock.observes(x.threadId, x)
}

val causalityCovering: Covering<Event> = Covering { y ->
    y.dependencies + (y.parent?.let { listOf(it) } ?: listOf())
}

fun emptyClock() = VectorClock<Int, Event>(programOrder)

/**
 * Execution represents a set of events belonging to single program's execution.
 */
class Execution(threadEvents: Map<Int, List<Event>> = emptyMap()) {
    /**
     * Execution is encoded as a mapping `ThreadID -> List<Event>`
     * from thread id to a list of events belonging to this thread ordered by program-order.
     * We also assume that program order is compatible with execution order,
     * and thus events within the same thread are also ordered by execution order.
     *
     * TODO: use array instead of map?
     */
    private val threadsEvents: MutableMap<Int, ArrayList<Event>> =
        threadEvents.map { (threadId, events) -> threadId to ArrayList(events) }.toMap().toMutableMap()

    val threads: Set<Int>
        get() = threadsEvents.keys

    fun addEvent(event: Event) {
        val threadEvents = threadsEvents.getOrPut(event.threadId) { arrayListOf() }
        check(event.parent == threadEvents.lastOrNull())
        threadEvents.add(event)
    }

    fun getThreadSize(iThread: Int): Int =
        threadsEvents[iThread]?.size ?: 0

    fun firstEvent(iThread: Int): Event? =
        threadsEvents[iThread]?.firstOrNull()

    fun lastEvent(iThread: Int): Event? =
        threadsEvents[iThread]?.lastOrNull()

    operator fun get(iThread: Int, Position: Int): Event? =
        threadsEvents[iThread]?.getOrNull(Position)

    operator fun contains(event: Event): Boolean =
        threadsEvents[event.threadId]?.let { events -> events.binarySearch(event) >= 0 } ?: false

}

/**
 * ExecutionFrontier represents a frontier of an execution,
 * that is the set of program-order maximal events of the execution.
 */
class ExecutionFrontier(frontier: Map<Int, Event> = emptyMap()) {

    /**
     * Frontier is encoded as a mapping `ThreadID -> Event` from the thread id
     * to the last executed event in this thread in the given execution.
     *
     * TODO: use array instead of map?
     */
    private val frontier: MutableMap<Int, Event> = frontier.toMutableMap()

    fun update(event: Event) {
        check(event.parent == frontier[event.threadId])
        frontier[event.threadId] = event
    }

    fun getPosition(iThread: Int): Int =
        frontier[iThread]?.threadPosition ?: -1

    operator fun get(iThread: Int): Event? =
        frontier[iThread]

    operator fun set(iThread: Int, event: Event) {
        check(iThread == event.threadId)
        // TODO: properly document this precondition
        //  (i.e. we expect frontier to be updated to some offspring of frontier's execution)
        check(programOrder.nullOrLessOrEqual(event.parent, frontier[iThread]))
        frontier[iThread] = event
    }

    operator fun contains(event: Event): Boolean {
        val lastEvent = frontier[event.threadId] ?: return false
        return programOrder.lessOrEqual(event, lastEvent)
    }

    fun copy(): ExecutionFrontier =
        ExecutionFrontier(frontier)

    fun toExecution(): Execution {
        return Execution(frontier.map {(threadId, lastEvent) ->
            var event: Event? = lastEvent
            val events = arrayListOf<Event>()
            while (event != null) {
                events.add(event)
                event = event.parent
            }
            threadId to events.apply { reverse() }
        }.toMap())
    }

}

abstract class Inconsistency

class InconsistentExecutionException(reason: Inconsistency): Exception(reason.toString())

interface ConsistencyChecker {
    fun check(execution: Execution): Inconsistency?
}

interface IncrementalConsistencyChecker {

    /**
     * Checks whether adding the given [event] to the current execution retains execution's consistency.
     *
     * @return `null` if execution remains consistent,
     *   otherwise returns non-null [Inconsistency] object
     *   representing the reason of inconsistency.
     */
    fun check(event: Event): Inconsistency?

    /**
     * Resets the internal state of consistency checker to [execution]
     * with [event] being the next event to start exploration from.
     *
     * @return `null` if the given execution is consistent,
     *   otherwise returns non-null [Inconsistency] object
     *   representing the reason of inconsistency.
     *
     * TODO: split resetting and consistency checking logic!
     */
    fun reset(event: Event, execution: Execution): Inconsistency?
}

class EventStructure(
    val initialThreadId: Int,
    val checkers: List<ConsistencyChecker> = listOf(),
    val incrementalCheckers: List<IncrementalConsistencyChecker> = listOf()
) {

    val root: Event

    /**
     * Stores a mapping `ThreadID -> Event` from the thread id
     * to the root event of the thread. It is guaranteed that this root event
     * has label of type [ThreadLabel] with kind [ThreadLabelKind.ThreadStart].
     *
     * TODO: use array instead of map?
     */
    private var threadRoots: MutableMap<Int, Event> = mutableMapOf()

    // TODO: this pattern is covered by explicit backing fields KEEP
    //   https://github.com/Kotlin/KEEP/issues/278
    private val _events: ArrayList<Event> = arrayListOf()

    /**
     * List of events of the event structure.
     */
    val events: List<Event> = _events

    private var currentExecution: Execution = Execution()

    private var currentFrontier: ExecutionFrontier = ExecutionFrontier()

    // TODO: rename?
    val currentExecutionEvents: List<Event>
        // TODO: just make an iterator for Execution class
        get() = events.filter { it in currentExecution }

    init {
        root = addRootEvent(initialThreadId)
    }

    fun startNextExploration(): Boolean {
        loop@while (true) {
            val event = rollbackToEvent { !it.visited }?.apply { visit() } ?: return false
            if (resetExecution(event) != null)
                continue@loop
            return true
        }
    }

    fun initializeExploration() {
        currentFrontier = ExecutionFrontier()
        currentFrontier[GHOST_THREAD_ID] = root
    }

    private fun rollbackToEvent(predicate: (Event) -> Boolean): Event? {
        val eventIdx = _events.indexOfLast(predicate)
        _events.subList(eventIdx + 1, _events.size).clear()
        // TODO: make a function to search for event in the execution-order sorted list
        threadRoots.entries.retainAll { (_, event) -> events.binarySearch(event) >= 0 }
        return events.lastOrNull()
    }

    private fun resetExecution(event: Event): Inconsistency? {
        currentExecution = event.frontier.toExecution()
        for (checker in checkers) {
            checker.check(currentExecution)?.let { return it }
        }
        for (checker in incrementalCheckers) {
            checker.reset(event, currentExecution)?.let { return it }
        }
        return null
    }

    fun checkConsistency(): Inconsistency? {
        for (checker in checkers) {
            checker.check(currentExecution)?.let { return it }
        }
        return null
    }

    fun getThreadRoot(iThread: Int): Event? =
        when (iThread) {
            GHOST_THREAD_ID -> root
            else -> threadRoots[iThread]
        }

    fun isInitializedThread(iThread: Int): Boolean =
        iThread == GHOST_THREAD_ID || threadRoots.contains(iThread)

    private fun createEvent(label: EventLabel, dependencies: List<Event>): Event {
        // We assume that at least one of the events participating into synchronization
        // is a request event, and the result of synchronization is response event.
        // We also assume that request and response parts aggregate.
        // Thus, we use `aggregatesWith` method to find among the list
        // of dependencies the parent event of newly added synchronized event.
        val parents = dependencies.filter { it.label.aggregatesWith(label) }
        check(dependencies.isNotEmpty() implies (parents.size == 1))
        val parent = parents.firstOrNull() ?: currentFrontier[label.threadId]
        // To prevent causality cycles to appear we check that
        // dependencies do not causally depend on predecessor.
        check(dependencies.all { dependency -> !causalityOrder.lessThan(parent!!, dependency) })
        return Event.create(
            label = label,
            parent = parent,
            // We also remove predecessor from the list of dependencies.
            // TODO: do not remove parent from dependencies?
            dependencies = dependencies.filter { it != parent },
            frontier = currentFrontier.copy()
        )
    }

    private fun addEvent(label: EventLabel, dependencies: List<Event>): Event {
        check(label.isThreadInitializer implies !isInitializedThread(label.threadId)) {
            "Thread ${label.threadId} is already initialized."
        }
        check(!label.isThreadInitializer implies isInitializedThread(label.threadId)) {
            "Thread ${label.threadId} should be initialized before new events can be added to it."
        }
        return createEvent(label, dependencies).also { event ->
            _events.add(event)
            if (label.isThreadInitializer)
                threadRoots[label.threadId] = event
            for (checker in incrementalCheckers) {
                checker.check(event)?.let { throw InconsistentExecutionException(it) }
            }
        }
    }

    private fun addEventToCurrentExecution(event: Event, visit: Boolean = true) {
        if (visit) { event.visit() }
        if (!inReplayMode(event.threadId))
            currentExecution.addEvent(event)
        currentFrontier.update(event)
    }

    private fun inReplayMode(iThread: Int): Boolean {
        val frontEvent = currentFrontier[iThread]?.also { check(it in currentExecution) }
        return (frontEvent != currentExecution.lastEvent(iThread))
    }

    private fun tryReplayEvent(iThread: Int): Event? {
        return if (inReplayMode(iThread)) {
            val position = 1 + currentFrontier.getPosition(iThread)
            check(position < currentExecution.getThreadSize(iThread))
            currentExecution[iThread, position]!!.also { addEventToCurrentExecution(it) }
        } else null
    }

    private fun addRootEvent(initialThreadId: Int): Event {
        // we do not mark root event as visited purposefully;
        // this is just a trick to make first call to `startNextExploration`
        // to pick the root event as the next event to explore from.
        val label = ThreadForkLabel(
            threadId = GHOST_THREAD_ID,
            setOf(initialThreadId)
        )
        return addEvent(label, emptyList())!!.also {
            addEventToCurrentExecution(it, visit = false)
        }
    }

    /**
     * Adds to the event structure a list of events obtained as a result of synchronizing given [event]
     * with the events contained in the current exploration. For example, if
     * `e1 @ A` is the given event labeled by `A` and `e2 @ B` is some event in the event structure labeled by `B`,
     * then the resulting list will contain event labeled by `C = A \+ B` if `C` is defined (i.e. not null),
     * and the list of dependencies of this new event will be equal to `listOf(e1, e2)`.
     *
     * @return list of added events
     */
    private fun addSynchronizedEvents(event: Event): List<Event> {
        // TODO: we should maintain an index of read/write accesses to specific memory location
        // TODO: pre-filter some trivially inconsistent candidates
        //  (e.g. write synchronizing with program-order preceding read)
        val candidateEvents = when {
            event.label.isTotal -> currentExecutionEvents.filterNot { causalityOrder.lessThan(it, event) }
            else -> currentExecutionEvents
        }
        return when (event.label.syncKind) {
            SynchronizationKind.Binary ->
                addBinarySynchronizedEvents(event, candidateEvents)
            SynchronizationKind.Barrier ->
                addBarrierSynchronizedEvents(event, candidateEvents)?.let { listOf(it) } ?: emptyList()
        }
    }

    private fun addBinarySynchronizedEvents(event: Event, candidateEvents: List<Event>): List<Event> {
        require(event.label.isBinarySynchronizing)
        // TODO: sort resulting events according to some strategy?
        return candidateEvents.mapNotNull {
            val syncLab = event.label.synchronize(it.label) ?: return@mapNotNull null
            val dependencies = listOf(event, it)
            addEvent(syncLab, dependencies)
        }
    }

    private fun addBarrierSynchronizedEvents(event: Event, candidateEvents: List<Event>): Event? {
        require(event.label.isBarrierSynchronizing)
        val (syncLab, dependencies) =
            candidateEvents.fold(event.label to listOf(event)) { (lab, deps), candidateEvent ->
                val resultLabel = candidateEvent.label.synchronize(lab)
                if (resultLabel != null)
                    (resultLabel to deps + candidateEvent)
                else (lab to deps)
            }
        return when {
            syncLab.isCompetedResponse -> addEvent(syncLab, dependencies)
            else -> null
        }
    }

    private fun addRequestEvent(label: EventLabel): Event {
        require(label.isRequest)
        tryReplayEvent(label.threadId)?.let { event ->
            check(label == event.label)
            return event
        }
        return addEvent(label, emptyList()).also {
            addEventToCurrentExecution(it)
        }
    }

    private fun addResponseEvents(requestEvent: Event): Pair<Event?, List<Event>> {
        require(requestEvent.label.isRequest)
        tryReplayEvent(requestEvent.threadId)?.let { event ->
            check(event.label.isResponse)
            // TODO: check that response label is compatible with request label
            // check(label == event.label)
            return event to listOf(event)
        }
        val responseEvents = addSynchronizedEvents(requestEvent)
        // TODO: use some other strategy to select the next event in the current exploration?
        // TODO: check consistency of chosen event!
        val chosenEvent = responseEvents.lastOrNull()?.also {
            addEventToCurrentExecution(it)
        }
        return (chosenEvent to responseEvents)
    }

    private fun addTotalEvent(label: EventLabel): Event {
        require(label.isTotal)
        tryReplayEvent(label.threadId)?.let { event ->
            check(label == event.label)
            return event
        }
        return addEvent(label, emptyList()).also {
            addEventToCurrentExecution(it)
            addSynchronizedEvents(it)
        }
    }

    fun addThreadStartEvent(iThread: Int): Event {
        val label = ThreadStartLabel(
            threadId = iThread,
            kind = LabelKind.Request
        )
        val requestEvent = addRequestEvent(label)
        val (responseEvent, responseEvents) = addResponseEvents(requestEvent)
        checkNotNull(responseEvent)
        check(responseEvents.size == 1)
        return responseEvent
    }

    fun addThreadFinishEvent(iThread: Int): Event {
        val label = ThreadFinishLabel(
            threadId = iThread,
        )
        return addTotalEvent(label)
    }

    fun addThreadForkEvent(iThread: Int, forkThreadIds: Set<Int>): Event {
        val label = ThreadForkLabel(
            threadId = iThread,
            forkThreadIds = forkThreadIds
        )
        return addTotalEvent(label)
    }

    fun addThreadJoinEvent(iThread: Int, joinThreadIds: Set<Int>): Event {
        val label = ThreadJoinLabel(
            threadId = iThread,
            kind = LabelKind.Request,
            joinThreadIds = joinThreadIds,
        )
        val requestEvent = addRequestEvent(label)
        val (responseEvent, responseEvents) = addResponseEvents(requestEvent)
        // TODO: handle case when ThreadJoin is not ready yet
        checkNotNull(responseEvent)
        check(responseEvents.size == 1)
        return responseEvent
    }

    fun addWriteEvent(iThread: Int, memoryLocationId: Int, value: Any?, typeDescriptor: String,
                      isExclusive: Boolean = false): Event {
        val label = MemoryAccessLabel(
            threadId = iThread,
            kind = LabelKind.Total,
            accessKind = MemoryAccessKind.Write,
            typeDesc = typeDescriptor,
            memId = memoryLocationId,
            value = value,
            isExclusive = isExclusive,
        )
        return addTotalEvent(label)
    }

    fun addReadEvent(iThread: Int, memoryLocationId: Int, typeDescriptor: String,
                     isExclusive: Boolean = false): Event {
        // we lazily initialize memory location upon first read to this location
        initializeMemoryLocation(memoryLocationId, typeDescriptor)
        // we first create read-request event with unknown (null) value,
        // value will be filled later in read-response event
        val label = MemoryAccessLabel(
            threadId = iThread,
            kind = LabelKind.Request,
            accessKind = MemoryAccessKind.Read,
            typeDesc = typeDescriptor,
            memId = memoryLocationId,
            value = null,
            isExclusive = isExclusive,
        )
        val requestEvent = addRequestEvent(label)
        val (responseEvent, _) = addResponseEvents(requestEvent)
        // TODO: think again --- is it possible that there is no write to read-from?
        //  Probably not, because in Kotlin variables are always initialized by default?
        //  What about initialization-related issues?
        checkNotNull(responseEvent)
        return responseEvent
    }

    private fun initializeMemoryLocation(memoryLocationId: Int, typeDescriptor: String) {
        // if there exists a write event to this location in the initialization thread,
        // (or the ghost thread, in which case memory location was already initialized)
        // then there is no need to add initialization write
        events.find {
            (it.threadId == GHOST_THREAD_ID || it.threadId == initialThreadId)
                && it.label.isMemoryAccessTo(memoryLocationId)
        }?.let { return }
        addWriteEvent(GHOST_THREAD_ID, memoryLocationId, defaultValueByDescriptor(typeDescriptor), typeDescriptor)
    }
}

class EventStructureMemoryTracker(private val eventStructure: EventStructure): MemoryTracker() {

    override fun writeValue(iThread: Int, memoryLocationId: Int, value: Any?, typeDescriptor: String) {
        eventStructure.addWriteEvent(iThread, memoryLocationId, value, typeDescriptor)
    }

    override fun readValue(iThread: Int, memoryLocationId: Int, typeDescriptor: String): Any? {
        val readEvent = eventStructure.addReadEvent(iThread, memoryLocationId, typeDescriptor)
        return (readEvent.label as MemoryAccessLabel).value
    }

    override fun compareAndSet(iThread: Int, memoryLocationId: Int, expectedValue: Any?, newValue: Any?, typeDescriptor: String): Boolean {
        val readEvent = eventStructure.addReadEvent(iThread, memoryLocationId, typeDescriptor, isExclusive = true)
        val readValue = (readEvent.label as MemoryAccessLabel).value
        if (readValue != expectedValue) return false
        eventStructure.addWriteEvent(iThread, memoryLocationId, newValue, typeDescriptor, isExclusive = true)
        return true
    }

    private enum class IncrementKind { Pre, Post }

    private fun fetchAndAdd(iThread: Int, memoryLocationId: Int, delta: Number,
                            typeDescriptor: String, incKind: IncrementKind): Number {
        val readEvent = eventStructure.addReadEvent(iThread, memoryLocationId, typeDescriptor, isExclusive = true)
        val readLabel = readEvent.label as MemoryAccessLabel
        // TODO: should we use some sub-type check instead of equality check?
        check(readLabel.typeDesc == typeDescriptor)
        val oldValue: Number = readLabel.value as Number
        val newValue: Number = when (typeDescriptor) {
            // check `Int` and `Long` types
            "I" -> (oldValue as Int) + (delta as Int)
            "J" -> (oldValue as Long) + (delta as Long)
            // TODO: should we also check for other types?
            else -> unreachable()
        }
        eventStructure.addWriteEvent(iThread, memoryLocationId, newValue, typeDescriptor, isExclusive = true)
        return when (incKind) {
            IncrementKind.Pre -> oldValue
            IncrementKind.Post -> newValue
        }
    }

    override fun getAndAdd(iThread: Int, memoryLocationId: Int, delta: Number, typeDescriptor: String): Number {
        return fetchAndAdd(iThread, memoryLocationId, delta, typeDescriptor, IncrementKind.Pre)
    }

    override fun addAndGet(iThread: Int, memoryLocationId: Int, delta: Number, typeDescriptor: String): Number {
        return fetchAndAdd(iThread, memoryLocationId, delta, typeDescriptor, IncrementKind.Post)
    }

}

// auxiliary ghost thread containing root event of the event structure
// and initialization events (e.g. initialization writes of memory locations)
private const val GHOST_THREAD_ID = -1
