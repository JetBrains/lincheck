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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.utils.*
import kotlin.reflect.KClass

class EventStructure(
    nParallelThreads: Int,
    val checker: ConsistencyChecker = idleConsistencyChecker,
    val incrementalChecker: IncrementalConsistencyChecker = idleIncrementalConsistencyChecker,
    val memoryInitializer: MemoryInitializer,
) {
    val mainThreadId = nParallelThreads
    val initThreadId = nParallelThreads + 1

    private val maxThreadId = initThreadId
    private val nThreads = maxThreadId + 1

    val root: Event

    // TODO: this pattern is covered by explicit backing fields KEEP
    //   https://github.com/Kotlin/KEEP/issues/278
    private val _events = sortedMutableListOf<Event>()

    /**
     * List of events of the event structure.
     */
    val events: SortedList<Event> = _events

    lateinit var currentExplorationRoot: Event
        private set

    // TODO: this pattern is covered by explicit backing fields KEEP
    //   https://github.com/Kotlin/KEEP/issues/278
    private var _currentExecution = MutableExecution(this.nThreads)

    val currentExecution: Execution
        get() = _currentExecution

    private var playedFrontier = MutableExecutionFrontier(this.nThreads)

    private var pinnedEvents = ExecutionFrontier(this.nThreads)

    private val currentRemapping: Remapping = Remapping()

    private val delayedConsistencyCheckBuffer = mutableListOf<Event>()

    private var detectedInconsistency: Inconsistency? = null

    /*
     * Map from blocked dangling events to their responses.
     * If event is blocked but the corresponding response has not yet arrived then it is mapped to null.
     */
    private val danglingEvents = mutableMapOf<Event, Event?>()

    init {
        root = addRootEvent()
    }

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
        playedFrontier = MutableExecutionFrontier(nThreads)
        playedFrontier[initThreadId] = currentExecution[initThreadId]!!.last()
    }

    fun abortExploration() {
        // _currentExecution = playedFrontier.toExecution()
        // println("played frontier: ${playedFrontier.mapping}")
        // TODO: bugfix --- cut threads absent in playedFrontier.mapping.values to 0 !!!
        for (threadId in currentExecution.threadIDs) {
            val lastEvent = playedFrontier[threadId]
            if (lastEvent == null) {
                _currentExecution.cut(threadId, 0)
                continue
            }
            when {
                // we handle blocking request in a special way --- we include their response part
                // in order to detect potential blocking response uniqueness violations
                // (e.g. two lock events unblocked by the same unlock event)
                // TODO: too complicated, try to simplify
                lastEvent.label.isRequest && lastEvent.label.isBlocking -> {
                    val responseEvent = _currentExecution[lastEvent.threadId, lastEvent.threadPosition + 1]
                        ?: continue
                    if (responseEvent.dependencies.any { it !in playedFrontier }) {
                        _currentExecution.cut(responseEvent)
                        continue
                    }
                    check(responseEvent.label.isResponse)
                    responseEvent.label.remap(currentRemapping)
                    _currentExecution.cutNext(responseEvent)
                }
                // otherwise just cut last replayed event
                else -> {
                    _currentExecution.cutNext(lastEvent)
                }
            }
        }
    }

    private fun rollbackToEvent(predicate: (Event) -> Boolean): Event? {
        val eventIdx = _events.indexOfLast(predicate)
        _events.subList(eventIdx + 1, _events.size).clear()
        return events.lastOrNull()
    }

    private fun resetExploration(event: Event) {
        currentExplorationRoot = event
        // TODO: filter unused initialization events
        _currentExecution = event.frontier.toMutableExecution().apply {
            removeDanglingRequestEvents()
        }
        pinnedEvents = event.pinnedEvents.copy().apply {
            cutDanglingRequestEvents()
        }.ensure {
            it.threadMap.values.all { pinned ->
                pinned in currentExecution
            }
        }
        currentRemapping.reset()
        danglingEvents.clear()
        delayedConsistencyCheckBuffer.clear()
        detectedInconsistency = null
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
            val replayedExecution = currentExplorationRoot.frontier.toMutableExecution().apply {
                // remove dangling request events, similarly as we do in `resetExploration`
                removeDanglingRequestEvents()
                // temporarily remove new event in order to reset incremental checker
                removeLastEvent(currentExplorationRoot)
            }
            // reset internal state of incremental checker
            incrementalChecker.reset(replayedExecution)
            // copy delayed events from the buffer and reset it
            val delayedEvents = delayedConsistencyCheckBuffer.toMutableList()
            delayedConsistencyCheckBuffer.clear()
            // run incremental checker on delayed events
            for (delayedEvent in delayedEvents) {
                replayedExecution.addEvent(delayedEvent)
                // TODO: refactor this check!!!
                if (delayedEvent.label.isSend) {
                    addSynchronizedEvents(delayedEvent)
                }
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
    fun getNextEventToReplay(iThread: Int): Event =
        currentExecution[iThread, playedFrontier.getNextPosition(iThread)]!!

    // should only be called in replay phase!
    private fun getNextHyperEventToReplay(iThread: Int): HyperEvent =
        currentExecution.nextAtomicEvent(iThread, playedFrontier.getNextPosition(iThread))!!

    // should only be called in replay phase!
    fun canReplayNextEvent(iThread: Int): Boolean {
        val atomicEvent = getNextHyperEventToReplay(iThread)
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

    private fun emptyClock(): MutableVectorClock =
        MutableVectorClock(nThreads)

    private fun VectorClock.toMutableFrontier(): MutableExecutionFrontier =
        (0 until nThreads).mapNotNull { tid ->
            currentExecution[tid, this[tid]]?.let { tid to it }
        }.let {
            mutableExecutionFrontierOf(*it.toTypedArray())
        }

    private fun createEvent(iThread: Int, label: EventLabel, parent: Event?,
                            dependencies: List<Event>, conflicts: List<Event>): Event? {
        var causalityViolation = false
        // Check that parent does not depend on conflicting events.
        if (parent != null) {
            causalityViolation = causalityViolation || conflicts.any { conflict ->
                causalityOrder.lessOrEqual(conflict, parent)
            }
        }
        // Also check that dependencies do not causally depend on conflicting events.
        causalityViolation = causalityViolation || conflicts.any { conflict -> dependencies.any { dependency ->
                causalityOrder.lessOrEqual(conflict, dependency)
        }}
        if (causalityViolation)
            return null
        val threadPosition = parent?.let { it.threadPosition + 1 } ?: 0
        val causalityClock = dependencies.fold(parent?.causalityClock?.copy() ?: emptyClock()) { clock, event ->
            clock + event.causalityClock
        }
        val frontier = currentExecution.toMutableFrontier().apply {
            cut(conflicts)
        }
        val pinnedEvents = pinnedEvents.copy().apply {
            cut(conflicts)
            merge(causalityClock.toMutableFrontier())
        }
        return Event.create(
            threadId = iThread,
            threadPosition = threadPosition,
            label = label,
            parent = parent,
            dependencies = dependencies,
            causalityClock = causalityClock.apply {
                set(iThread, threadPosition)
            },
            frontier = frontier,
            pinnedEvents = pinnedEvents,
        )
    }

    private fun addEvent(iThread: Int, label: EventLabel, parent: Event?, dependencies: List<Event>): Event? {
        require(parent !in dependencies)
        val conflicts = conflictingEvents(iThread, parent?.let { it.threadPosition + 1 } ?: 0, label, dependencies)
        return createEvent(iThread, label, parent, dependencies, conflicts)?.also { event ->
            _events.add(event)
        }
    }

    private fun conflictingEvents(iThread: Int, position: Int, label: EventLabel, dependencies: List<Event>): List<Event> {
        val conflicts = mutableListOf<Event>()
        // if current execution already has an event in given position --- then it is conflict
        currentExecution[iThread, position]?.also { conflicts.add(it) }
        // handle label specific cases
        // TODO: unify this logic for various kinds of labels?
        when {
            // lock-response synchronizing with our unlock is conflict
            label is LockLabel && label.isResponse && !label.isReentry -> run {
                require(dependencies.size == 1)
                val unlock = dependencies.first()
                currentExecution.forEach { event ->
                    if (event.label is LockLabel && event.label.isResponse &&
                        event.label.mutex == label.mutex && event.locksFrom == unlock) {
                        conflicts.add(event)
                    }
                }
            }
            // wait-response synchronizing with our notify is conflict
            label is WaitLabel && label.isResponse -> run {
                require(dependencies.size == 1)
                val notify = dependencies.first()
                if ((notify.label as NotifyLabel).isBroadcast)
                    return@run
                currentExecution.forEach { event ->
                    if (event.label is WaitLabel && event.label.isResponse &&
                        event.label.mutex == label.mutex && event.notifiedBy == notify) {
                        conflicts.add(event)
                    }
                }
            }
            // TODO: add similar rule for read-exclusive-response?
        }
        return conflicts
    }

    private fun addEventToCurrentExecution(event: Event, visit: Boolean = true, synchronize: Boolean = false) {
        if (visit) {
            event.visit()
        }
        val isReplayedEvent = inReplayPhase(event.threadId)
        if (!isReplayedEvent) {
            _currentExecution.addEvent(event)
        }
        playedFrontier.update(event)
        // mark last replayed blocking event as dangling
        if (event.label.isRequest && event.label.isBlocking &&
            isReplayedEvent && !inReplayPhase(event.threadId)) {
            markBlockedDanglingRequest(event)
        }
        // unmark dangling request if its response was added
        if (event.label.isResponse && event.label.isBlocking &&
            event.parent in danglingEvents) {
            unmarkBlockedDanglingRequest(event.parent!!)
        }
        if (synchronize && !inReplayPhase()) {
            addSynchronizedEvents(event)
        }
        // TODO: set suddenInvocationResult instead
        if (detectedInconsistency == null) {
            detectedInconsistency = checkConsistencyIncrementally(event, isReplayedEvent && event != currentExplorationRoot)
        }
    }

    private fun synchronizationCandidates(event: Event): List<Event> {
        // consider all candidates in current execution and apply some general filters
        val candidates = currentExecution.asSequence()
            // for send event we filter out ...
            .runIf(event.label.isSend) { filter {
                // (1) all of its causal predecessors, because an attempt to synchronize with
                //     these predecessors will result in causality cycle
                !causalityOrder.lessThan(it, event) &&
                // (2) pinned events, because their response part is pinned (i.e. fixed),
                //     unless pinned event is blocking dangling event
                (!pinnedEvents.contains(it) || danglingEvents.contains(it))
            }}
        return when {
            // for read-request events we search for the last write to the same memory location
            // in the same thread, and then filter out all causal predecessors of this last write,
            // because these events are "obsolete" --- reading from them will result in coherence cycle
            // and will violate consistency
            event.label is MemoryAccessLabel && event.label.isRequest -> {
                // val threadLastWrite = currentExecution[event.threadId]?.lastOrNull {
                //     it.label is WriteAccessLabel && it.label.location == event.label.location
                // } ?: root
                val threadReads = currentExecution[event.threadId]!!.filter {
                    it.label is ReadAccessLabel && it.label.isResponse && it.label.location == event.label.location
                }
                val lastSeenWrite = threadReads.lastOrNull()?.readsFrom
                val staleWrites = threadReads
                    .map { it.readsFrom }
                    .filter { it != lastSeenWrite }
                    .distinct()
                val racyWrites = calculateRacyWrites(event.label.location, event.causalityClock.toMutableFrontier())
                candidates.filter {
                    // !causalityOrder.lessThan(it, threadLastWrite) &&
                    !racyWrites.any { write -> causalityOrder.lessThan(it, write) } &&
                    !staleWrites.any { write -> causalityOrder.lessOrEqual(it, write) }
                }
            }
            // re-entry lock-request synchronizes only with the initial event
            event.label is LockLabel && event.label.isRequest && event.label.isReentry -> {
                return listOf(root)
            }
            // re-entry unlock synchronizes with nothing
            event.label is UnlockLabel && event.label.isReentry -> {
                return listOf(root)
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
        val syncEvents = when(event.label.syncType) {
            SynchronizationType.Binary -> addBinarySynchronizedEvents(event, candidateEvents)
            SynchronizationType.Barrier -> addBarrierSynchronizedEvents(event, candidateEvents)
        }
        // if there are responses to blocked dangling requests, then set the response of one of these requests
        for (syncEvent in syncEvents) {
            val requestEvent = syncEvent.parent
                ?.takeIf { it.label.isRequest && it.label.isBlocking }
                ?: continue
            if (requestEvent in danglingEvents && getUnblockingResponse(requestEvent) == null) {
                setUnblockingResponse(syncEvent)
                break
            }
        }
        return syncEvents
    }

    private fun addBinarySynchronizedEvents(event: Event, candidateEvents: Collection<Event>): List<Event> {
        require(event.label.isBinarySynchronizing)
        // TODO: sort resulting events according to some strategy?
        return candidateEvents
            .mapNotNull { other ->
                val syncLab = event.label.synchronize(other.label) ?: return@mapNotNull null
                val (parent, dependency) = when {
                    event.label.isRequest -> event to other
                    other.label.isRequest -> other to event
                    else -> unreachable()
                }
                check(parent.label.isRequest && dependency.label.isSend && syncLab.isResponse)
                Triple(syncLab, parent, dependency)
            }.sortedBy { (_, _, dependency) ->
                dependency
            }.mapNotNull { (syncLab, parent, dependency) ->
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
        val responseEvent = addEvent(parent.threadId, syncLab, parent, dependencies.filter { it != parent })
        return listOfNotNull(responseEvent)
    }

    private fun addRootEvent(): Event {
        // we do not mark root event as visited purposefully;
        // this is just a trick to make first call to `startNextExploration`
        // to pick the root event as the next event to explore from.
        val label = InitializationLabel(memoryInitializer)
        return addEvent(initThreadId, label, parent = null, dependencies = emptyList())!!.also {
            addEventToCurrentExecution(it, visit = false)
        }
    }

    private fun addSendEvent(iThread: Int, label: EventLabel): Event {
        require(label.isSend)
        tryReplayEvent(iThread)?.let { event ->
            // TODO: also check custom event/label specific rules when replaying,
            //   e.g. upon replaying write-exclusive check its location equal to
            //   the location of previous read-exclusive part
            event.label.replay(label, currentRemapping)
            addEventToCurrentExecution(event)
            return event
        }
        val parent = playedFrontier[iThread]
        val dependencies = mutableListOf<Event>()
        return addEvent(iThread, label, parent, dependencies)!!.also { event ->
            addEventToCurrentExecution(event, synchronize = true)
        }
    }

    private fun addRequestEvent(iThread: Int, label: EventLabel): Event {
        require(label.isRequest)
        tryReplayEvent(iThread)?.let { event ->
            event.label.replay(label, currentRemapping)
            addEventToCurrentExecution(event)
            return event
        }
        val parent = playedFrontier[iThread]
        return addEvent(iThread, label, parent, dependencies = emptyList())!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    private fun addResponseEvents(requestEvent: Event): Pair<Event?, List<Event>> {
        require(requestEvent.label.isRequest)
        tryReplayEvent(requestEvent.threadId)?.let { event ->
            check(event.label.isResponse)
            check(event.parent == requestEvent)
            // TODO: refactor & move to other replay-related functions
            val readyToReplay = event.dependencies.all {
                dependency -> dependency in playedFrontier
            }
            if (!readyToReplay) {
                return (null to listOf())
            }
            // TODO: replace with `synchronizesInto` check
            val label = event.dependencies.fold (event.parent.label) { label: EventLabel?, dependency ->
                label?.synchronize(dependency.label)
            }
            check(label != null)
            event.label.replay(label, currentRemapping)
            addEventToCurrentExecution(event)
            return event to listOf(event)
        }
        if (requestEvent.label.isBlocking && requestEvent in danglingEvents) {
            val event = getUnblockingResponse(requestEvent)
                ?: return (null to listOf())
            check(event.label.isResponse)
            check(event.parent == requestEvent)
            addEventToCurrentExecution(event)
            return event to listOf(event)
        }
        val responseEvents = addSynchronizedEvents(requestEvent)
        if (responseEvents.isEmpty()) {
            markBlockedDanglingRequest(requestEvent)
            return (null to listOf())
        }
        // TODO: use some other strategy to select the next event in the current exploration?
        // TODO: check consistency of chosen event!
        val chosenEvent = responseEvents.last().also { event ->
            addEventToCurrentExecution(event)
        }
        return (chosenEvent to responseEvents)
    }

    fun isBlockedRequest(request: Event): Boolean {
        require(request.label.isRequest && request.label.isBlocking)
        return (request == playedFrontier[request.threadId])
    }

    fun isBlockedDanglingRequest(request: Event): Boolean {
        require(request.label.isRequest && request.label.isBlocking)
        return (request == currentExecution[request.threadId]?.last())
    }

    fun isBlockedAwaitingRequest(request: Event): Boolean {
        require(isBlockedRequest(request))
        if (inReplayPhase(request.threadId)) {
            return !canReplayNextEvent(request.threadId)
        }
        if (request in danglingEvents) {
            return danglingEvents[request] == null
        }
        return false
    }

    fun getBlockedRequest(iThread: Int): Event? =
        playedFrontier[iThread]?.takeIf { it.label.isRequest && it.label.isBlocking }

    fun getBlockedAwaitingRequest(iThread: Int): Event? =
        getBlockedRequest(iThread)?.takeIf { isBlockedAwaitingRequest(it) }

    private fun markBlockedDanglingRequest(request: Event) {
        require(isBlockedDanglingRequest(request))
        check(request !in danglingEvents)
        check(danglingEvents.keys.all { it.threadId != request.threadId })
        danglingEvents.put(request, null).ensureNull()
    }

    private fun unmarkBlockedDanglingRequest(request: Event) {
        require(request.label.isRequest && request.label.isBlocking)
        require(!isBlockedDanglingRequest(request))
        require(request in danglingEvents)
        danglingEvents.remove(request)
    }

    private fun setUnblockingResponse(response: Event) {
        require(response.label.isResponse && response.label.isBlocking)
        val request = response.parent
            .ensure { it != null }
            .ensure { isBlockedDanglingRequest(it!!) }
            .ensure { it in danglingEvents }
        danglingEvents.put(request!!, response).ensureNull()
    }

    private fun getUnblockingResponse(request: Event): Event? {
        require(isBlockedDanglingRequest(request))
        require(request in danglingEvents)
        return danglingEvents[request]
    }

    fun addThreadStartEvent(iThread: Int): Event {
        val label = ThreadStartLabel(
            threadId = iThread,
            kind = LabelKind.Request,
            isMainThread = (iThread == mainThreadId)
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

    fun addWriteEvent(iThread: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?,
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

    fun addLockRequestEvent(iThread: Int, mutex: OpaqueValue, reentranceDepth: Int = 1, reentranceCount: Int = 1, isWaitLock: Boolean = false): Event {
        val label = LockLabel(
            kind = LabelKind.Request,
            mutex_ = mutex,
            reentranceDepth = reentranceDepth,
            reentranceCount = reentranceCount,
            isWaitLock = isWaitLock,
        )
        return addRequestEvent(iThread, label)
    }

    fun addLockResponseEvent(lockRequest: Event): Event? {
        require(lockRequest.label.isRequest && lockRequest.label is LockLabel)
        return addResponseEvents(lockRequest).first
    }

    fun addUnlockEvent(iThread: Int, mutex: OpaqueValue, reentranceDepth: Int = 1, reentranceCount: Int = 1, isWaitUnlock: Boolean = false): Event {
        val label = UnlockLabel(
            mutex_ = mutex,
            reentranceDepth = reentranceDepth,
            reentranceCount = reentranceCount,
            isWaitUnlock = isWaitUnlock,
        )
        return addSendEvent(iThread, label)
    }

    fun addWaitRequestEvent(iThread: Int, mutex: OpaqueValue): Event {
        val label = WaitLabel(
            kind = LabelKind.Request,
            mutex_ = mutex,
        )
        return addRequestEvent(iThread, label)

    }

    fun addWaitResponseEvent(waitRequest: Event): Event? {
        require(waitRequest.label.isRequest && waitRequest.label is WaitLabel)
        return addResponseEvents(waitRequest).first
    }

    fun addNotifyEvent(iThread: Int, mutex: OpaqueValue, isBroadcast: Boolean): Event {
        // TODO: we currently ignore isBroadcast flag and handle `notify` similarly as `notifyAll`.
        //   It is correct wrt. Java's semantics, since `wait` can wake-up spuriously according to the spec.
        //   Thus multiple wake-ups due to single notify can be interpreted as spurious.
        //   However, if one day we will want to support wait semantics without spurious wake-ups
        //   we will need to revisit this.
        val label = NotifyLabel(mutex, isBroadcast)
        return addSendEvent(iThread, label)
    }

    fun addParkRequestEvent(iThread: Int): Event {
        val label = ParkLabel(LabelKind.Request, iThread)
        return addRequestEvent(iThread, label)
    }

    fun addParkResponseEvent(parkRequest: Event): Event? {
        require(parkRequest.label.isRequest && parkRequest.label is ParkLabel)
        return addResponseEvents(parkRequest).first
    }

    fun addUnparkEvent(iThread: Int, unparkingThreadId: Int): Event {
        val label = UnparkLabel(unparkingThreadId)
        return addSendEvent(iThread, label)
    }

    /**
     * Calculates the view for specific memory location observed at the given point of execution
     * given by [observation] vector clock. Memory location view is a vector clock itself
     * that maps each thread id to the last write access event to the given memory location at the given thread.
     *
     * @param location the memory location.
     * @param observation the vector clock specifying the point of execution for the view calculation.
     * @return the view (i.e. vector clock) for the given memory location.
     *
     * TODO: move to Execution?
     */
    fun calculateMemoryLocationView(location: MemoryLocation, observation: ExecutionFrontier): ExecutionFrontier =
        observation.threadMap.mapNotNull { tid, event ->
            check(event in currentExecution)
            var lastWrite = event
            while (lastWrite.label.asMemoryAccessLabel(location)?.takeIf { it.isWrite } == null) {
                lastWrite = lastWrite.parent ?: return@mapNotNull null
            }
            (tid to lastWrite)
        }.let {
            executionFrontierOf(*it.toTypedArray())
        }

    /**
     * Calculates a list of all racy writes to specific memory location observed at the given point of execution
     * given by [observation] vector clock. In other words, the resulting list contains all program-order maximal
     * racy writes observed at the given point.
     *
     * @param location the memory location.
     * @param observation the vector clock specifying the point of execution for the view calculation.
     * @return list of program-order maximal racy write events.
     *
     * TODO: move to Execution?
     */
    fun calculateRacyWrites(location: MemoryLocation, observation: ExecutionFrontier): List<Event> {
        val frontier = calculateMemoryLocationView(location, observation)
        return frontier.threadMap.values.filter { write ->
            !frontier.threadMap.values.any { other ->
                causalityOrder.lessThan(write, other)
            }
        }
    }

}