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

typealias ThreadSwitchCallback = (Int) -> Unit

class EventStructure(
    nThreads: Int,
    val checker: ConsistencyChecker = idleConsistencyChecker,
    val incrementalChecker: IncrementalConsistencyChecker = idleIncrementalConsistencyChecker,
    // TODO: refactor this!
    val threadSwitchCallback: ThreadSwitchCallback = {},
) {
    val initialThreadId = nThreads
    val rootThreadId = nThreads + 1
    val maxThreadId = rootThreadId

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

    private fun emptyFrontier(): ExecutionFrontier =
        ExecutionFrontier().apply { set(rootThreadId, root) }

    private fun emptyExecution(): Execution =
        emptyFrontier().toExecution()

    fun getThreadRoot(iThread: Int): Event? =
        currentExecution.firstEvent(iThread)?.also { event ->
            check(event.label.isThreadInitializer)
        }

    fun isStartedThread(iThread: Int): Boolean =
        getThreadRoot(iThread) != null

    fun isFinishedThread(iThread: Int): Boolean =
        currentExecution.lastEvent(iThread)?.let { event ->
            event.label is ThreadFinishLabel
        } ?: false

    fun startNextExploration(): Boolean {
        loop@while (true) {
            val event = rollbackToEvent { !it.visited }?.apply { visit() }
                ?: return false
            resetExecution(event)
            return true
        }
    }

    fun initializeExploration() {
        currentFrontier = emptyFrontier()
    }

    private fun rollbackToEvent(predicate: (Event) -> Boolean): Event? {
        val eventIdx = _events.indexOfLast(predicate)
        _events.subList(eventIdx + 1, _events.size).clear()
        return events.lastOrNull()
    }

    private fun resetExecution(event: Event) {
        _currentExecution = event.frontier.toExecution()
        pinnedEvents = event.pinnedEvents.copy()
    }

    fun checkConsistency(): Inconsistency? =
        checker.check(currentExecution)

    private fun checkConsistencyIncrementally(event: Event, isReplayedEvent: Boolean): Inconsistency? {
        // if we are not replaying the event then just run all necessary consistency checks
        if (!isReplayedEvent) {
            return incrementalChecker.check(event)
        }
        // if we finished replay phase we need to reset internal state of incremental checker
        // and to check full consistency of the new execution before we start to explore it further
        if (!inReplayPhase()) {
            // the new event being visited is the last event in the event structure,
            // we assume that it is the last event to be replayed
            check(event == events.last())
            // we temporarily remove new event in order to reset incremental checker
            _currentExecution.removeLastEvent(event)
            // reset internal state of incremental checker
            incrementalChecker.reset(_currentExecution)
            // add new event back
             _currentExecution.addEvent(event)
            // first run incremental checker to have an opportunity to find an inconsistency earlier
            incrementalChecker.check(event)?.let { return it }
            // then run heavyweight full consistency check
            return checker.check(_currentExecution)
        }
        return null
    }

    fun inReplayPhase(): Boolean =
        (0 .. maxThreadId).any { inReplayPhase(it) }

    fun inReplayPhase(iThread: Int): Boolean {
        val frontEvent = currentFrontier[iThread]?.also { check(it in _currentExecution) }
        return (frontEvent != currentExecution.lastEvent(iThread))
    }

    // should only be called in replay phase!
    fun canReplayNextEvent(iThread: Int): Boolean {
        val event = currentExecution[iThread, currentFrontier.getNextPosition(iThread)]!!
        // delay replaying the last event till all other events are replayed;
        if (event == events.last()) {
            return (0 .. maxThreadId).all { it == iThread || !inReplayPhase(it) }
        }
        return event.dependencies.all { dependency ->
            dependency in currentFrontier
        }
    }

    private fun tryReplayEvent(iThread: Int): Event? {
        return if (inReplayPhase(iThread)) {
            val position = 1 + currentFrontier.getPosition(iThread)
            check(position < currentExecution.getThreadSize(iThread))
            currentExecution[iThread, position]!!
        } else null
    }

    private fun waitForReplay(iThread: Int) {
        val threadInReplayPhase = inReplayPhase(iThread)
        if ( threadInReplayPhase && !canReplayNextEvent(iThread) ||
            !threadInReplayPhase && !isFinishedThread(iThread) && inReplayPhase()) {
            threadSwitchCallback(iThread)
        }
    }

    private fun createEvent(iThread: Int, label: EventLabel, parent: Event?, dependencies: List<Event>): Event {
        // To prevent causality cycles to appear we check that
        // dependencies do not causally depend on predecessor.
        check(dependencies.all { dependency -> !causalityOrder.lessThan(parent!!, dependency) })
        val cutPosition = parent?.let { it.threadPosition + 1 } ?: 0
        return Event.create(
            threadId = iThread,
            label = label,
            parent = parent,
            // TODO: rename to external dependencies?
            dependencies = dependencies.filter { it != parent },
            frontier = cutFrontier(iThread, cutPosition, currentFrontier),
            pinnedEvents = cutFrontier(iThread, cutPosition, pinnedEvents),
        )
    }

    private fun cutFrontier(iThread: Int, position: Int, frontier: ExecutionFrontier): ExecutionFrontier {
        val nextEvent = currentExecution[iThread, position] ?: return frontier.copy()
        return ExecutionFrontier(frontier.mapping.mapNotNull { (threadId, frontEvent) ->
            require(frontEvent in currentExecution)
            // TODO: optimize using binary search
            var event: Event = frontEvent
            while (event.causalityClock.observes(nextEvent.threadId, nextEvent)) {
                event = event.parent ?: return@mapNotNull null
            }
            threadId to event
        }.toMap())
    }

    private fun addEvent(iThread: Int, label: EventLabel, parent: Event?, dependencies: List<Event>): Event {
        return createEvent(iThread, label, parent,  dependencies).also { event ->
            _events.add(event)
        }
    }

    private fun addEventToCurrentExecution(event: Event, visit: Boolean = true, synchronize: Boolean = false) {
        if (visit) { event.visit() }
        val isReplayedEvent = inReplayPhase(event.threadId)
        if (!isReplayedEvent)
            _currentExecution.addEvent(event)
        currentFrontier.update(event)
        if (synchronize) { addSynchronizedEvents(event) }
        checkConsistencyIncrementally(event, isReplayedEvent)?.let {
            throw InconsistentExecutionException(it)
        }
        if (isReplayedEvent) {
            waitForReplay(event.threadId)
        }
    }

    private fun synchronizationCandidates(event: Event): List<Event> {
        val predicates = mutableListOf<(Event) -> Boolean>()

        // for total event we filter out all of its causal predecessors,
        // because an attempt to synchronize with these predecessors will result in causality cycle
        if (event.label.isTotal) {
            predicates.add { !causalityOrder.lessThan(it, event) && !pinnedEvents.contains(it) }
        }

        // for read-request events we search for the last write to the same memory location
        // in the same thread, and then filter out all causal predecessors of this last write,
        // because these events are "obsolete" --- reading from them will result in coherence cycle
        // and will violate consistency
        if (event.label.isRequest && event.label is MemoryAccessLabel) {
            require(event.label.isRead)
            val threadLastWrite = currentExecution[event.threadId]?.lastOrNull {
                it.label is MemoryAccessLabel && it.label.isWrite && it.label.memId == event.label.memId
            } ?: root
            predicates.add { !causalityOrder.lessThan(it, threadLastWrite) }
        }

        return currentExecution.filter {
            for (predicate in predicates) {
                if (!predicate(it))
                    return@filter false
            }
            return@filter true
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
        val candidateEvents = synchronizationCandidates(event)
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
        return candidateEvents.mapNotNull { other ->
            val syncLab = event.label.synchronize(other.label) ?: return@mapNotNull null
            val (parent, dependency) = when {
                event.label.aggregatesWith(syncLab) -> event to other
                other.label.aggregatesWith(syncLab) -> other to event
                else -> unreachable()
            }
            addEvent(parent.threadId, syncLab, parent, dependencies = listOf(dependency))
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
        if (!(syncLab.isResponse && syncLab.isCompleted))
            return null
        // We assume that at least one of the events participating into synchronization
        // is a request event, and the result of synchronization is response event.
        // We also assume that request and response parts aggregate.
        // Thus, we use `aggregatesWith` method to find among the list
        // of dependencies the parent event of newly added synchronized event.
        val parent = dependencies.first { it.label.aggregatesWith(syncLab) }
        return addEvent(parent.threadId, syncLab, parent, dependencies.filter { it != parent })
    }

    private fun addRootEvent(): Event {
        // we do not mark root event as visited purposefully;
        // this is just a trick to make first call to `startNextExploration`
        // to pick the root event as the next event to explore from.
        val label = InitializationLabel()
        return addEvent(rootThreadId, label, parent = null, dependencies = emptyList()).also {
            addEventToCurrentExecution(it, visit = false)
        }
    }

    private fun addTotalEvent(iThread: Int, label: EventLabel): Event {
        require(label.isTotal)
        tryReplayEvent(iThread)?.let { event ->
            event.label.replay(label).also { check(it) }
            addEventToCurrentExecution(event)
            return event
        }
        val parent = currentFrontier[iThread]
        return addEvent(iThread, label, parent, dependencies = emptyList()).also { event ->
            addEventToCurrentExecution(event, synchronize = true)
        }
    }

    private fun addRequestEvent(iThread: Int, label: EventLabel): Event {
        require(label.isRequest)
        tryReplayEvent(iThread)?.let { event ->
            event.label.replay(label).also { check(it) }
            addEventToCurrentExecution(event)
            return event
        }
        val parent = currentFrontier[iThread]
        return addEvent(iThread, label, parent, dependencies = emptyList()).also { event ->
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
            addEventToCurrentExecution(event)
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

    fun addThreadStartEvent(iThread: Int): Event {
        val label = ThreadStartLabel(
            threadId = iThread,
            kind = LabelKind.Request,
            isInitializationThread = (iThread == initialThreadId)
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, responseEvents) = addResponseEvents(requestEvent)
        checkNotNull(responseEvent)
        check(responseEvents.size == 1)
        return responseEvent
    }

    fun addThreadFinishEvent(iThread: Int): Event {
        val label = ThreadFinishLabel(
            threadId = iThread,
        )
        return addTotalEvent(iThread, label)
    }

    fun addThreadForkEvent(iThread: Int, forkThreadIds: Set<Int>): Event {
        val label = ThreadForkLabel(
            forkThreadIds = forkThreadIds
        )
        return addTotalEvent(iThread, label)
    }

    fun addThreadJoinEvent(iThread: Int, joinThreadIds: Set<Int>): Event {
        val label = ThreadJoinLabel(
            kind = LabelKind.Request,
            joinThreadIds = joinThreadIds,
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, responseEvents) = addResponseEvents(requestEvent)
        // TODO: handle case when ThreadJoin is not ready yet
        checkNotNull(responseEvent)
        check(responseEvents.size == 1)
        return responseEvent
    }

    fun addWriteEvent(iThread: Int, memoryLocationId: Int, value: OpaqueValue?, kClass: KClass<*>,
                      isExclusive: Boolean = false): Event {
        val label = AtomicMemoryAccessLabel(
            kind = LabelKind.Total,
            accessKind = MemoryAccessKind.Write,
            memId_ = memoryLocationId,
            value_ = value,
            kClass = kClass,
            isExclusive = isExclusive,
        )
        return addTotalEvent(iThread, label)
    }

    fun addReadEvent(iThread: Int, memoryLocationId: Int, kClass: KClass<*>,
                     isExclusive: Boolean = false): Event {
        // we first create read-request event with unknown (null) value,
        // value will be filled later in read-response event
        val label = AtomicMemoryAccessLabel(
            kind = LabelKind.Request,
            accessKind = MemoryAccessKind.Read,
            memId_ = memoryLocationId,
            value_ = null,
            kClass = kClass,
            isExclusive = isExclusive,
        )
        val requestEvent = addRequestEvent(iThread, label)
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