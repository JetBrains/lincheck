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

import org.jetbrains.kotlinx.lincheck.strategy.managed.MemoryTracker
import org.jetbrains.kotlinx.lincheck.strategy.managed.defaultValueByDescriptor
import kotlin.collections.set

class EventStructure(
    val initialThreadId: Int,
    val checkers: List<ConsistencyChecker> = listOf(),
    val incrementalCheckers: List<IncrementalConsistencyChecker> = listOf()
) {

    val root: Event

    /**
     * Stores a mapping `ThreadID -> Event` from the thread id
     * to the root event of the thread. It is guaranteed that this root event
     * has label of type [ThreadEventLabel] with kind [ThreadLabelKind.ThreadStart].
     *
     * TODO: use array instead of map?
     */
    private var threadRoots: MutableMap<Int, Event> = mutableMapOf()

    // TODO: this pattern is covered by explicit backing fields KEEP
    //   https://github.com/Kotlin/KEEP/issues/278
    private val _events: SortedArrayList<Event> = sortedArrayListOf()

    /**
     * List of events of the event structure.
     */
    val events: SortedList<Event> = _events

    // TODO: this pattern is covered by explicit backing fields KEEP
    //   https://github.com/Kotlin/KEEP/issues/278
    private var _currentExecution: MutableExecution = MutableExecution()

    val currentExecution: Execution
        get() = _currentExecution

    private var currentFrontier: ExecutionFrontier = ExecutionFrontier()

    init {
        root = addRootEvent()
    }

    fun startNextExploration(): Boolean {
        loop@while (true) {
            val event = rollbackToEvent { !it.visited }?.apply { visit() } ?: return false
            resetExecution(event)
            // first run incremental consistency checkers to have an opportunity
            // to find an inconsistency earlier
            if (checkConsistencyIncrementally(event) != null)
                continue@loop
            // then run heavyweight full consistency checks
            if (checkConsistency() != null)
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
        threadRoots.entries.retainAll { (_, threadRoot) -> threadRoot in events }
        return events.lastOrNull()
    }

    private fun resetExecution(event: Event) {
        _currentExecution = event.frontier.toExecution()
        // temporarily remove new event in order to reset incremental checkers
        _currentExecution.removeLast(event)
        for (checker in incrementalCheckers) {
            checker.reset(_currentExecution)
        }
        // add new event back
        _currentExecution.addEvent(event)
    }

    fun checkConsistency(): Inconsistency? {
        for (checker in checkers) {
            checker.check(currentExecution)?.let { return it }
        }
        return null
    }

    private fun checkConsistencyIncrementally(event: Event): Inconsistency? {
        for (checker in incrementalCheckers) {
            checker.check(event)?.let { return it }
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
            checkConsistencyIncrementally(event)?.let {
                throw InconsistentExecutionException(it)
            }
        }
    }

    private fun addEventToCurrentExecution(event: Event, visit: Boolean = true) {
        if (visit) { event.visit() }
        if (!inReplayMode(event.threadId))
            _currentExecution.addEvent(event)
        currentFrontier.update(event)
    }

    fun inReplayMode(iThread: Int): Boolean {
        val frontEvent = currentFrontier[iThread]?.also { check(it in _currentExecution) }
        return (frontEvent != currentExecution.lastEvent(iThread))
    }

    fun canReplayNextEvent(iThread: Int): Boolean {
        val nextEvent = currentExecution[iThread, 1 + currentFrontier.getPosition(iThread)]
        check(nextEvent != null)
        return nextEvent.dependencies.all { dependency ->
            dependency in currentFrontier
        }
    }

    private fun tryReplayEvent(iThread: Int): Event? {
        return if (inReplayMode(iThread)) {
            val position = 1 + currentFrontier.getPosition(iThread)
            check(position < currentExecution.getThreadSize(iThread))
            currentExecution[iThread, position]!!.also { addEventToCurrentExecution(it) }
        } else null
    }

    private fun addRootEvent(): Event {
        // we do not mark root event as visited purposefully;
        // this is just a trick to make first call to `startNextExploration`
        // to pick the root event as the next event to explore from.
        val label = InitializationLabel()
        return addEvent(label, emptyList()).also {
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
            event.label.isTotal -> currentExecution.filterNot { causalityOrder.lessThan(it, event) }
            else -> currentExecution
        }
        val syncEvents = arrayListOf<Event>()
        if (event.label.isBinarySynchronizing) {
            addBinarySynchronizedEvents(event, candidateEvents).let {
                syncEvents.addAll(it)
            }
        }
        if (event.label.isBarrierSynchronizing) {
            addBarrierSynchronizedEvents(event, candidateEvents)?.let {
                syncEvents.add(it)
            }
        }
        return syncEvents
    }

    private fun addBinarySynchronizedEvents(event: Event, candidateEvents: Collection<Event>): List<Event> {
        require(event.label.isBinarySynchronizing)
        // TODO: sort resulting events according to some strategy?
        return candidateEvents.mapNotNull {
            val syncLab = event.label.synchronize(it.label) ?: return@mapNotNull null
            val dependencies = listOf(event, it)
            addEvent(syncLab, dependencies)
        }
    }

    private fun addBarrierSynchronizedEvents(event: Event, candidateEvents: Collection<Event>): Event? {
        require(event.label.isBarrierSynchronizing)
        val (syncLab, dependencies) =
            candidateEvents.fold(event.label to listOf(event)) { (lab, deps), candidateEvent ->
                val resultLabel = candidateEvent.label.synchronize(lab)
                if (resultLabel != null)
                    (resultLabel to deps + candidateEvent)
                else (lab to deps)
            }
        return when {
            // TODO: think again whether we need `isResponse` check here
            syncLab.isResponse && syncLab.isCompleted -> addEvent(syncLab, dependencies)
            else -> null
        }
    }

    private fun addRequestEvent(label: EventLabel): Event {
        require(label.isRequest)
        tryReplayEvent(label.threadId)?.let { event ->
            event.label.replay(label).also { check(it) }
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
            check(event.parent == requestEvent)
            val label = event.dependencies.fold (event.parent.label) { label: EventLabel?, dependency ->
                label?.synchronize(dependency.label)
            }
            check(label != null)
            event.label.replay(label).also { check(it) }
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
            event.label.replay(label).also { check(it) }
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
            kind = LabelKind.Request,
            isInitializationThread = (iThread == initialThreadId)
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
        val label = AtomicMemoryAccessLabel(
            threadId = iThread,
            kind = LabelKind.Total,
            accessKind = MemoryAccessKind.Write,
            typeDescriptor = typeDescriptor,
            memId = memoryLocationId,
            value = value,
            isExclusive = isExclusive,
        )
        return addTotalEvent(label)
    }

    fun addReadEvent(iThread: Int, memoryLocationId: Int, typeDescriptor: String,
                     isExclusive: Boolean = false): Event {
        // we first create read-request event with unknown (null) value,
        // value will be filled later in read-response event
        val label = AtomicMemoryAccessLabel(
            threadId = iThread,
            kind = LabelKind.Request,
            accessKind = MemoryAccessKind.Read,
            typeDescriptor = typeDescriptor,
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
}

class EventStructureMemoryTracker(private val eventStructure: EventStructure): MemoryTracker() {

    override fun writeValue(iThread: Int, memoryLocationId: Int, value: Any?, typeDescriptor: String) {
        eventStructure.addWriteEvent(iThread, memoryLocationId, value, typeDescriptor)
    }

    override fun readValue(iThread: Int, memoryLocationId: Int, typeDescriptor: String): Any? {
        val readEvent = eventStructure.addReadEvent(iThread, memoryLocationId, typeDescriptor)
        return (readEvent.label as AtomicMemoryAccessLabel).value
    }

    override fun compareAndSet(iThread: Int, memoryLocationId: Int, expectedValue: Any?, newValue: Any?, typeDescriptor: String): Boolean {
        val readEvent = eventStructure.addReadEvent(iThread, memoryLocationId, typeDescriptor, isExclusive = true)
        val readValue = (readEvent.label as AtomicMemoryAccessLabel).value
        if (readValue != expectedValue) return false
        eventStructure.addWriteEvent(iThread, memoryLocationId, newValue, typeDescriptor, isExclusive = true)
        return true
    }

    private enum class IncrementKind { Pre, Post }

    private fun fetchAndAdd(iThread: Int, memoryLocationId: Int, delta: Number,
                            typeDescriptor: String, incKind: IncrementKind): Number {
        val readEvent = eventStructure.addReadEvent(iThread, memoryLocationId, typeDescriptor, isExclusive = true)
        val readLabel = readEvent.label as AtomicMemoryAccessLabel
        // TODO: should we use some sub-type check instead of equality check?
        check(readLabel.typeDescriptor == typeDescriptor)
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
     */
    fun reset(execution: Execution)
}
