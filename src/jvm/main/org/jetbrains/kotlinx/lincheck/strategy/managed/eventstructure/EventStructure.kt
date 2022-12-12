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
import kotlin.reflect.KClass

typealias ThreadSwitchCallback = (Int) -> Unit

class EventStructure(
    nThreads: Int,
    val checker: ConsistencyChecker = idleConsistencyChecker,
    val incrementalChecker: IncrementalConsistencyChecker = idleIncrementalConsistencyChecker,
    val lockAwareScheduling: Boolean = true,
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

    lateinit var currentExplorationRoot: Event
        private set

    // TODO: this pattern is covered by explicit backing fields KEEP
    //   https://github.com/Kotlin/KEEP/issues/278
    private var _currentExecution: MutableExecution = MutableExecution()

    val currentExecution: Execution
        get() = _currentExecution

    private var playedFrontier: ExecutionFrontier = ExecutionFrontier()

    private var pinnedEvents: ExecutionFrontier = ExecutionFrontier()

    private val delayedConsistencyCheckBuffer = mutableListOf<Event>()

    private var detectedInconsistency: Inconsistency? = null

    private var monitorTracker = createMonitorTracker()

    init {
        root = addRootEvent()
    }

    private fun emptyFrontier(): ExecutionFrontier =
        ExecutionFrontier().apply { set(rootThreadId, root) }

    private fun emptyExecution(): Execution =
        emptyFrontier().toExecution()

    fun getThreadRoot(iThread: Int): Event? =
        currentExecution.firstEvent(iThread)?.also { event ->
            check(event.label is ThreadStartLabel && event.label.isRequest)
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
            resetExploration(event)
            return true
        }
    }

    fun initializeExploration() {
        playedFrontier = emptyFrontier()
    }

    fun abortExploration() {
        _currentExecution = playedFrontier.toExecution()
    }

    private fun rollbackToEvent(predicate: (Event) -> Boolean): Event? {
        val eventIdx = _events.indexOfLast(predicate)
        _events.subList(eventIdx + 1, _events.size).clear()
        return events.lastOrNull()
    }

    private fun resetExploration(event: Event) {
        check(delayedConsistencyCheckBuffer.isEmpty())
        currentExplorationRoot = event
        _currentExecution = event.frontier.toExecution()
        pinnedEvents = event.pinnedEvents.copy()
        detectedInconsistency = null
        monitorTracker = createMonitorTracker()
    }

    fun checkConsistency(): Inconsistency? {
        if (detectedInconsistency == null) {
            detectedInconsistency = checker.check(currentExecution)
        }
        return detectedInconsistency
    }

    private fun checkConsistencyIncrementally(event: Event, isReplayedEvent: Boolean): Inconsistency? {
        if (inReplayPhase()) {
            // If we are in replay phase, but the event being added is not a replayed event,
            // then we need to save it to delayed events consistency check buffer,
            // so that we will be able to pass it to incremental consistency checker later.
            // This situation can occur when we are replaying instruction that produces several events.
            // For example, replaying the read part of read-modify-write instruction (like CAS)
            // can also create new event representing write part of this RMW.
            if (!isReplayedEvent) {
                delayedConsistencyCheckBuffer.add(event)
            }
            // In any case we do not run incremental consistency checks during replay phase,
            // because during this phase consistency checker has invalid internal state.
            return null
        }
        // If we are not in replay phase anymore, but the current event is replayed event,
        // it means that we just finished replay phase (i.e. the given event is the last replayed event).
        // In this case we need to do the following.
        //   (1) Reset internal state of incremental checker.
        //   (2) Run incremental checker on all delayed non-replayed events.
        //   (3) Check full consistency of the new execution before we start to explore it further.
        // We run incremental consistency checker before heavyweight full consistency check
        // in order to give it more lightweight incremental checker
        // an opportunity to find inconsistency earlier.
        if (isReplayedEvent) {
            val replayedExecution = currentExplorationRoot.frontier.toExecution()
            // we temporarily remove new event in order to reset incremental checker
            replayedExecution.removeLastEvent(currentExplorationRoot)
            // reset internal state of incremental checker
            incrementalChecker.reset(replayedExecution)
            // add current exploration root to delayed buffer too
            delayedConsistencyCheckBuffer.add(currentExplorationRoot)
            // copy delayed events from the buffer and reset it
            val delayedEvents = delayedConsistencyCheckBuffer.toMutableList()
            delayedConsistencyCheckBuffer.clear()
            // run incremental checker on delayed events
            for (delayedEvent in delayedEvents) {
                replayedExecution.addEvent(delayedEvent)
                incrementalChecker.check(delayedEvent)?.let { return it }
            }
            // to make sure that we have incrementally checked all newly added events
            check(replayedExecution == currentExecution)
            // finally run heavyweight full consistency check
            return checker.check(_currentExecution)
        }
        // If we are not in replay phase (and we have finished it before adding current event)
        // then just run incremental consistency checker.
        return incrementalChecker.check(event)
    }

    fun inReplayPhase(): Boolean =
        (0 .. maxThreadId).any { inReplayPhase(it) }

    fun inReplayPhase(iThread: Int): Boolean {
        val frontEvent = playedFrontier[iThread]?.ensure { it in _currentExecution }
        return (frontEvent != currentExecution.lastEvent(iThread))
    }

    // should only be called in replay phase!
    fun canReplayNextEvent(iThread: Int): Boolean {
        val nextPosition = playedFrontier.getNextPosition(iThread)
        val atomicEvent = currentExecution.nextAtomicEvent(iThread, nextPosition, replaying = true)!!
        // delay replaying the last event till all other events are replayed;
        if (currentExplorationRoot == atomicEvent.events.last()) {
            // TODO: prettify
            return (0 .. maxThreadId).all { it == iThread || !inReplayPhase(it) }
        }
        return atomicEvent.dependencies.all { dependency ->
            dependency in playedFrontier
        }
    }

    private fun tryReplayEvent(iThread: Int): Event? {
        return if (inReplayPhase(iThread)) {
            val position = 1 + playedFrontier.getPosition(iThread)
            check(position < currentExecution.getThreadSize(iThread))
            currentExecution[iThread, position]!!
        } else null
    }

    private fun createEvent(iThread: Int, label: EventLabel, parent: Event?,
                            dependencies: List<Event>, conflicts: List<Event>): Event {
        // Check that parent does not depend on conflicting events.
        parent?.ensure { conflicts.all { conflict ->
            !causalityOrder.lessOrEqual(conflict, parent)
        }}
        // Also check that dependencies do not causally depend on conflicting events.
        check(dependencies.all { dependency ->
            conflicts.all { conflict ->
                !causalityOrder.lessOrEqual(conflict, dependency)
            }
        })
        return Event.create(
            threadId = iThread,
            label = label,
            parent = parent,
            // TODO: rename to external dependencies?
            dependencies = dependencies.filter { it != parent },
            frontier = currentExecution.toFrontier().cut(conflicts),
            pinnedEvents = pinnedEvents.cut(conflicts),
        )
    }

    private fun addEvent(iThread: Int, label: EventLabel, parent: Event?, dependencies: List<Event>): Event {
        val conflicts = conflictingEvents(iThread, parent?.let { it.threadPosition + 1 } ?: 0, label, dependencies)
        return createEvent(iThread, label, parent, dependencies, conflicts).also { event ->
            _events.add(event)
        }
    }

    private fun conflictingEvents(iThread: Int, position: Int, label: EventLabel, dependencies: List<Event>): List<Event> {
        val conflicts = mutableListOf<Event>()
        // if current execution already has an event in given position --- then it is conflict
        currentExecution[iThread, position]?.also { conflicts.add(it) }
        // handle label specific cases
        // TODO: unify this logic for various kinds of labels?
        when (label) {
            // remove lock-response synchronizing with our unlock
            is LockLabel -> run {
                if (!label.isResponse)
                    return@run
                require(dependencies.size == 1)
                val unlock = dependencies.first()
                currentExecution.forEach { event ->
                    if (event.label is LockLabel && event.label.isResponse && event.locksFrom == unlock)
                        conflicts.add(event)
                }
            }
            // remove wait-response synchronizing with our notify
            is WaitLabel -> run {
                if (!label.isResponse)
                    return@run
                require(dependencies.size == 1)
                val notify = dependencies.first()
                if ((notify.label as NotifyLabel).isBroadcast)
                    return@run
                currentExecution.forEach { event ->
                    if (event.label is WaitLabel && event.label.isResponse && event.notifiedBy == notify)
                        conflicts.add(event)
                }
            }
            // TODO: add similar rule for read-exclusive-response?
        }
        return conflicts
    }

    private fun addEventToCurrentExecution(event: Event, visit: Boolean = true, synchronize: Boolean = false) {
        if (visit) { event.visit() }
        val isReplayedEvent = inReplayPhase(event.threadId)
        if (!isReplayedEvent)
            _currentExecution.addEvent(event)
        playedFrontier.update(event)
        if (synchronize)
            addSynchronizedEvents(event)
        // TODO: set suddenInvocationResult instead
        if (detectedInconsistency == null) {
            detectedInconsistency = checkConsistencyIncrementally(event, isReplayedEvent)
        }
    }

    private fun synchronizationCandidates(event: Event): List<Event> {
        val candidates = currentExecution.asSequence()
            // for send event we filter out all of its causal predecessors,
            // because an attempt to synchronize with these predecessors will result in causality cycle
            .runIf(event.label.isSend) { filter {
                !causalityOrder.lessThan(it, event) && !pinnedEvents.contains(it)
            }}
        return when {
            // for read-request events we search for the last write to the same memory location
            // in the same thread, and then filter out all causal predecessors of this last write,
            // because these events are "obsolete" --- reading from them will result in coherence cycle
            // and will violate consistency
            event.label is MemoryAccessLabel && event.label.isRequest -> {
                require(event.label.isRead)
                val threadLastWrite = currentExecution[event.threadId]?.lastOrNull {
                    it.label is WriteAccessLabel && it.label.location == event.label.location
                } ?: root
                candidates.filter { !causalityOrder.lessThan(it, threadLastWrite) }
            }

            event.label is LockLabel && event.label.isRequest -> {
                // re-entry lock-request synchronizes only with the initial event
                if (event.label.isReentry)
                    return listOf(root)
                candidates
            }

            else -> candidates
        }.toList()
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
        return when(event.label.syncType) {
            SynchronizationType.Binary -> addBinarySynchronizedEvents(event, candidateEvents)
            SynchronizationType.Barrier -> addBarrierSynchronizedEvents(event, candidateEvents)
        }
    }

    private fun addBinarySynchronizedEvents(event: Event, candidateEvents: Collection<Event>): List<Event> {
        require(event.label.isBinarySynchronizing)
        // TODO: sort resulting events according to some strategy?
        return candidateEvents.mapNotNull { other ->
            val syncLab = event.label.synchronize(other.label) ?: return@mapNotNull null
            val (parent, dependency) = when {
                event.label.isRequest -> event to other
                other.label.isRequest -> other to event
                else -> unreachable()
            }
            check(parent.label.isRequest && dependency.label.isSend && syncLab.isResponse)
            addEvent(parent.threadId, syncLab, parent, dependencies = listOf(dependency))
        }
    }

    private fun addBarrierSynchronizedEvents(event: Event, candidateEvents: Collection<Event>): List<Event> {
        require(event.label.isBarrierSynchronizing)
        val (syncLab, dependencies) =
            candidateEvents.fold(event.label to listOf(event)) { (lab, deps), candidateEvent ->
                candidateEvent.label.synchronize(lab)?.let {
                    (it to deps + candidateEvent)
                } ?: (lab to deps)
            }
        if (syncLab.isBlocking && !syncLab.unblocked)
            return listOf()
        // We assume that at most one of the events participating into synchronization
        // is a request event, and the result of synchronization is response event.
        check(syncLab.isResponse)
        val parent = dependencies.first { it.label.isRequest }
        val event = addEvent(parent.threadId, syncLab, parent, dependencies.filter { it != parent })
        return listOf(event)
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

    private fun addSendEvent(iThread: Int, label: EventLabel): Event {
        require(label.isSend)
        tryReplayEvent(iThread)?.let { event ->
            // TODO: also check custom event/label specific rules when replaying,
            //   e.g. upon replaying write-exclusive check its location equal to
            //   the location of previous read-exclusive part
            event.label.replay(label).also { check(it) }
            addEventToCurrentExecution(event)
            return event
        }
        val parent = playedFrontier[iThread]
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
        val parent = playedFrontier[iThread]
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

    private fun getBlockedEvent(iThread: Int, label: EventLabel): Event? {
        require(label.isRequest && label.isBlocking)
        return playedFrontier[iThread]?.takeIf { it.label == label }
    }

    private fun isBlockedEvent(event: Event): Boolean {
        require(event.label.isRequest && event.label.isBlocking)
        require(event == playedFrontier[event.threadId])
        // block last event in the thread during replay phase
        if (inReplayPhase() && !inReplayPhase(event.threadId))
            // TODO: such events should be considered pending?
            return true
        // block event if its response part cannot be replayed yet
        if (inReplayPhase(event.threadId) && !canReplayNextEvent(event.threadId))
            return true
        // TODO: do we need to handle other cases?
        return false
    }

    fun addThreadStartEvent(iThread: Int): Event {
        val label = ThreadStartLabel(
            threadId = iThread,
            kind = LabelKind.Request,
            isMainThread = (iThread == initialThreadId)
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
        return addSendEvent(iThread, label)
    }

    fun addThreadForkEvent(iThread: Int, forkThreadIds: Set<Int>): Event {
        val label = ThreadForkLabel(
            forkThreadIds = forkThreadIds
        )
        return addSendEvent(iThread, label)
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

    fun addWriteEvent(iThread: Int, location: MemoryLocation, value: OpaqueValue?, kClass: KClass<*>,
                      isExclusive: Boolean = false): Event {
        val label = WriteAccessLabel(
            location_ = location,
            value_ = value,
            kClass = kClass,
            isExclusive = isExclusive,
        )
        return addSendEvent(iThread, label)
    }

    fun addReadEvent(iThread: Int, location: MemoryLocation, kClass: KClass<*>,
                     isExclusive: Boolean = false): Event {
        // we first create read-request event with unknown (null) value,
        // value will be filled later in read-response event
        val label = ReadAccessLabel(
            kind = LabelKind.Request,
            location_ = location,
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

    fun addLockEvent(iThread: Int, mutex: Any): Event {
        val depth = 1 + monitorTracker.reentranceDepth(iThread, mutex)
        val label = LockLabel(
            kind = LabelKind.Request,
            mutex_ = mutex,
            reentranceDepth = depth
        )
        // take the last lock-request event or create new one
        val requestEvent = getBlockedEvent(iThread, label)
            ?: addRequestEvent(iThread, label)
        // if event is blocked then postpone addition of lock-response event
        if (isBlockedEvent(requestEvent))
            return requestEvent
        // if lock is acquired by another thread then also postpone addition of lock-response event
        if (!monitorTracker.acquire(iThread, mutex))
            return requestEvent
        // otherwise add lock-response event
        val (responseEvent, _) = addResponseEvents(requestEvent)
        checkNotNull(responseEvent)
        return responseEvent
    }

    fun addUnlockEvent(iThread: Int, mutex: Any): Event {
        val depth = monitorTracker.reentranceDepth(iThread, mutex)
        val label = UnlockLabel(
            mutex_ = mutex,
            reentranceDepth = depth
        )
        return addSendEvent(iThread, label).also {
            monitorTracker.release(iThread, mutex)
        }
    }

    fun addWaitEvent(iThread: Int, mutex: Any): Event {
        val label = WaitLabel(
            kind = LabelKind.Request,
            mutex_ = mutex,
        )
        // take the last wait-request event or create new one
        val requestEvent = getBlockedEvent(iThread, label) ?: run {
            // also add releasing unlock event before wait-request
            val depth = monitorTracker.reentranceDepth(iThread, mutex)
            val unlockLabel = UnlockLabel(
                mutex,
                reentranceDepth = depth,
                reentranceCount = depth,
            )
            addSendEvent(iThread, unlockLabel)
            addRequestEvent(iThread, label)
        }
        // if event is blocked then postpone addition of wait-response event
        if (isBlockedEvent(requestEvent))
            return requestEvent
        // if we need to wait then postpone addition of the wait-response event
        if (monitorTracker.wait(iThread, mutex)) {
            return requestEvent
        }
        // otherwise try to add the wait-response event
        val (responseEvent, _) = addResponseEvents(requestEvent)
        // if wait-response is currently unavailable return wait-request
        if (responseEvent == null) {
            return requestEvent
        }
        // otherwise also add acquiring lock event
        val depth = monitorTracker.reentranceDepth(iThread, mutex)
        val lockLabel = LockLabel(
            kind = LabelKind.Request,
            mutex_ = mutex,
            reentranceDepth = depth
        )
        val lockRequestEvent = addRequestEvent(iThread, lockLabel)
        val (lockResponseEvent, _) = addResponseEvents(lockRequestEvent)
        checkNotNull(lockResponseEvent)
        // finally, return wait-response event
        return responseEvent
    }

    fun addNotifyEvent(iThread: Int, mutex: Any, isBroadcast: Boolean): Event {
        // TODO: we currently ignore isBroadcast flag and handle `notify` similarly as `notifyAll`.
        //   It is correct wrt. Java's semantics, since `wait` can wake-up spuriously according to the spec.
        //   Thus multiple wake-ups due to single notify can be interpreted as spurious.
        //   However, if one day we will want to support wait semantics without spurious wake-ups
        //   we will need to revisit this.
        val label = NotifyLabel(mutex, isBroadcast)
        return addSendEvent(iThread, label).also {
            monitorTracker.notify(iThread, mutex, isBroadcast)
        }
    }

    fun isWaiting(iThread: Int): Boolean =
        monitorTracker.isWaiting(iThread)

    fun lockReentranceDepth(iThread: Int, monitor: Any): Int =
        monitorTracker.reentranceDepth(iThread, monitor)

    private fun createMonitorTracker(): MonitorTracker =
        if (lockAwareScheduling)
            MapMonitorTracker(maxThreadId)
        else
            LockReentranceCounter(maxThreadId)

    private class LockReentranceCounter(val nThreads: Int) : MonitorTracker {
        private val map = mutableMapOf<Any, IntArray>()

        override fun acquire(iThread: Int, monitor: Any): Boolean {
            val reentrance = map.computeIfAbsent(monitor) { IntArray(nThreads) }
            reentrance[iThread]++
            return true
        }

        override fun release(iThread: Int, monitor: Any) {
            val reentrance = map.computeIfAbsent(monitor) { IntArray(nThreads) }
            check(reentrance[iThread] > 0)
            reentrance[iThread]--
        }

        override fun reentranceDepth(iThread: Int, monitor: Any): Int =
            map[monitor]?.get(iThread) ?: 0

        override fun wait(iThread: Int, monitor: Any): Boolean = false

        override fun notify(iThread: Int, monitor: Any, notifyAll: Boolean) {}

        override fun isWaiting(iThread: Int): Boolean = false

    }

}