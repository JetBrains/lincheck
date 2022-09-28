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
import org.jetbrains.kotlinx.lincheck.strategy.managed.OpaqueValue
import kotlin.reflect.KClass

class EventStructure(
    val nThreads: Int,
    val checkers: List<ConsistencyChecker> = listOf(),
    val incrementalCheckers: List<IncrementalConsistencyChecker> = listOf()
) {
    val initialThreadId = nThreads

    val root: Event

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

    private var pinnedEvents: ExecutionFrontier = ExecutionFrontier()

    init {
        root = addRootEvent()
    }

    fun startNextExploration(): Boolean {
        loop@while (true) {
            val event = rollbackToEvent { !it.visited }?.apply { visit() }
                ?: return false
            resetExecution(event)
            // TODO: calling consistency checkers before finishing Replaying phase
            //   is error-prone, because until this phase is over
            //   execution graph can be in inconsistent state
            //   (e.g. memory location ID's of synchronizing events do not match).
            //   Think about how we can still safely call consistency checks when replay phase is over.
            // first run incremental consistency checkers to have an opportunity
            // to find an inconsistency earlier
//             if (checkConsistencyIncrementally(event) != null)
//                continue@loop
            // then run heavyweight full consistency checks
//            if (checkConsistency() != null)
//                continue@loop
            return true
        }
    }

    fun initializeExploration() {
        currentFrontier = emptyFrontier()
    }

    private fun emptyFrontier(): ExecutionFrontier =
        ExecutionFrontier().apply { set(GHOST_THREAD_ID, root) }

    private fun emptyExecution(): Execution =
        emptyFrontier().toExecution()

    private fun rollbackToEvent(predicate: (Event) -> Boolean): Event? {
        val eventIdx = _events.indexOfLast(predicate)
        _events.subList(eventIdx + 1, _events.size).clear()
        return events.lastOrNull()
    }

    private fun resetExecution(event: Event) {
        _currentExecution = event.frontier.toExecution()
        pinnedEvents = event.pinnedEvents.copy()
        //   TODO: think how to safely reset state of incremental consistency checker
        //    while reusing intermediate memorized state of this checker.
        //    See comment in StartNextExploration().
        // temporarily remove new event in order to reset incremental checkers
        // _currentExecution.removeLast(event)
        for (checker in incrementalCheckers) {
            // checker.reset(_currentExecution)
            checker.reset(emptyExecution())
        }
        // add new event back
        // _currentExecution.addEvent(event)
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
        currentExecution.firstEvent(iThread)?.also {event ->
            check(event.label.isThreadInitializer)
        }

    fun isInitializedThread(iThread: Int): Boolean =
        getThreadRoot(iThread) != null

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
            frontier = currentFrontier.copy(),
            pinnedEvents = pinnedEvents.copy()
        )
    }

    private fun addEvent(label: EventLabel, dependencies: List<Event>): Event {
        return createEvent(label, dependencies).also { event ->
            _events.add(event)
        }
    }

    private fun addEventToCurrentExecution(event: Event, visit: Boolean = true, synchronize: Boolean = false) {
        if (visit) { event.visit() }
        if (!inReplayPhase(event.threadId))
            _currentExecution.addEvent(event)
        currentFrontier.update(event)
        if (synchronize) { addSynchronizedEvents(event) }
        checkConsistencyIncrementally(event)?.let {
            throw InconsistentExecutionException(it)
        }
    }

    fun inReplayPhase(): Boolean =
        (0 .. nThreads).any { inReplayPhase(it) }

    fun inReplayPhase(iThread: Int): Boolean {
        val frontEvent = currentFrontier[iThread]?.also { check(it in _currentExecution) }
        return (frontEvent != currentExecution.lastEvent(iThread))
    }

    // should only be called in replay phase!
    fun canReplayNextEvent(iThread: Int): Boolean {
        val aggregated = currentExecution.getAggregatedLabel(iThread, currentFrontier.getNextPosition(iThread))
        check(aggregated != null) {
            "There is no next event to replay"
        }
        val (_, events) = aggregated
        // delay replaying the last event till all other events are replayed;
        // this is because replaying last event can lead to addition of new events;
        // for example, in case of RMWs replaying exclusive read can lead to addition
        // of new event representing write exclusive part
        // TODO: this problem could be handled better if we had an opportunity to
        //   suspend execution of operation in ManagedStrategy in the middle (see comment below).
        if (this.events.last() in events) {
            return (0 .. nThreads).all { it == iThread || !inReplayPhase(it) }
        }
        // TODO: unify with the similar code in SequentialConsistencyChecker
        // TODO: maybe add an opportunity for ManagedStrategy to suspend
        //   reading operation in the middle (i.e. after Request past, but before Response).
        //   This could also be useful for implementing scheduling strategies
        //   (e.g. write-first strategy that tries to first schedule threads whose next operation is write).
        return events.all { it.dependencies.all { dependency ->
            dependency in currentFrontier
        }}
    }

    private fun tryReplayEvent(iThread: Int): Event? {
        return if (inReplayPhase(iThread)) {
            val position = 1 + currentFrontier.getPosition(iThread)
            check(position < currentExecution.getThreadSize(iThread))
            currentExecution[iThread, position]!!.also { event ->
                addEventToCurrentExecution(event)
            }
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
        val candidateEvents = when {
            event.label.isTotal -> currentExecution.filter {
                !causalityOrder.lessThan(it, event) && !pinnedEvents.contains(it)
            }
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
            syncLab.isResponse && syncLab.isCompleted ->
                addEvent(syncLab, dependencies)
            else -> null
        }
    }

    private fun addRequestEvent(label: EventLabel): Event {
        require(label.isRequest)
        tryReplayEvent(label.threadId)?.let { event ->
            event.label.replay(label).also { check(it) }
            return event
        }
        return addEvent(label, emptyList()).also { event ->
            addEventToCurrentExecution(event)
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
        val chosenEvent = responseEvents.lastOrNull()?.also { event ->
            addEventToCurrentExecution(event)
        }
        return (chosenEvent to responseEvents)
    }

    private fun addTotalEvent(label: EventLabel): Event {
        require(label.isTotal)
        tryReplayEvent(label.threadId)?.let { event ->
            event.label.replay(label).also { check(it) }
            return event
        }
        return addEvent(label, emptyList()).also { event ->
            addEventToCurrentExecution(event, synchronize = true)
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

    fun addWriteEvent(iThread: Int, memoryLocationId: Int, value: OpaqueValue?, kClass: KClass<*>,
                      isExclusive: Boolean = false): Event {
        val label = AtomicMemoryAccessLabel(
            threadId = iThread,
            kind = LabelKind.Total,
            accessKind = MemoryAccessKind.Write,
            memId_ = memoryLocationId,
            value_ = value,
            kClass = kClass,
            isExclusive = isExclusive,
        )
        return addTotalEvent(label)
    }

    fun addReadEvent(iThread: Int, memoryLocationId: Int, kClass: KClass<*>,
                     isExclusive: Boolean = false): Event {
        // we first create read-request event with unknown (null) value,
        // value will be filled later in read-response event
        val label = AtomicMemoryAccessLabel(
            threadId = iThread,
            kind = LabelKind.Request,
            accessKind = MemoryAccessKind.Read,
            memId_ = memoryLocationId,
            value_ = null,
            kClass = kClass,
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

    override fun writeValue(iThread: Int, memoryLocationId: Int, value: OpaqueValue?, kClass: KClass<*>) {
        eventStructure.addWriteEvent(iThread, memoryLocationId, value, kClass)
    }

    override fun readValue(iThread: Int, memoryLocationId: Int, kClass: KClass<*>): OpaqueValue? {
        val readEvent = eventStructure.addReadEvent(iThread, memoryLocationId, kClass)
        return (readEvent.label as AtomicMemoryAccessLabel).value
    }

    override fun compareAndSet(iThread: Int, memoryLocationId: Int, expected: OpaqueValue?, desired: OpaqueValue?,
                               kClass: KClass<*>): Boolean {
        val readEvent = eventStructure.addReadEvent(iThread, memoryLocationId, kClass, isExclusive = true)
        val value = (readEvent.label as AtomicMemoryAccessLabel).value
        if (value != expected)
            return false
        eventStructure.addWriteEvent(iThread, memoryLocationId, desired, kClass, isExclusive = true)
        return true
    }

    private enum class IncrementKind { Pre, Post }

    private fun fetchAndAdd(iThread: Int, memoryLocationId: Int, delta: Number,
                            kClass: KClass<*>, incKind: IncrementKind): OpaqueValue? {
        val readEvent = eventStructure.addReadEvent(iThread, memoryLocationId, kClass, isExclusive = true)
        val readLabel = readEvent.label as AtomicMemoryAccessLabel
        // TODO: should we use some sub-type check instead of equality check?
        check(readLabel.kClass == kClass)
        val oldValue = readLabel.value!!
        val newValue = oldValue + delta
        eventStructure.addWriteEvent(iThread, memoryLocationId, newValue, kClass, isExclusive = true)
        return when (incKind) {
            IncrementKind.Pre -> oldValue
            IncrementKind.Post -> newValue
        }
    }

    override fun getAndAdd(iThread: Int, memoryLocationId: Int, delta: Number, kClass: KClass<*>): OpaqueValue? {
        return fetchAndAdd(iThread, memoryLocationId, delta, kClass, IncrementKind.Pre)
    }

    override fun addAndGet(iThread: Int, memoryLocationId: Int, delta: Number, kClass: KClass<*>): OpaqueValue? {
        return fetchAndAdd(iThread, memoryLocationId, delta, kClass, IncrementKind.Post)
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
     * Resets the internal state of consistency checker to [execution].
     */
    fun reset(execution: Execution)
}
