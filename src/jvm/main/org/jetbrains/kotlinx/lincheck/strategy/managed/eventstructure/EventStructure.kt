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
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.*
import org.jetbrains.kotlinx.lincheck.utils.*
import java.util.*
import kotlin.reflect.KClass

class EventStructure(
    nParallelThreads: Int,
    val memoryInitializer: MemoryInitializer,
    val loopDetector: LoopDetector,
    // TODO: refactor --- avoid using callbacks!
    val reportInconsistencyCallback: ReportInconsistencyCallback,
    val internalThreadSwitchCallback: InternalThreadSwitchCallback,
) {
    val mainThreadId = nParallelThreads
    val initThreadId = nParallelThreads + 1

    private val maxThreadId = initThreadId
    private val nThreads = maxThreadId + 1

    val syncAlgebra: SynchronizationAlgebra = AtomicSynchronizationAlgebra

    val root: AtomicThreadEvent

    // TODO: this pattern is covered by explicit backing fields KEEP
    //   https://github.com/Kotlin/KEEP/issues/278
    private val _events = sortedMutableListOf<BacktrackableEvent>()

    /**
     * List of events of the event structure.
     */
    val events: SortedList<Event> = _events

    lateinit var currentExplorationRoot: Event
        private set

    // TODO: this pattern is covered by explicit backing fields KEEP
    //   https://github.com/Kotlin/KEEP/issues/278
    private var _currentExecution = MutableExecution<AtomicThreadEvent>(this.nThreads)

    val currentExecution: Execution<AtomicThreadEvent>
        get() = _currentExecution

    private var playedFrontier = MutableExecutionFrontier<AtomicThreadEvent>(this.nThreads)

    private var replayer = Replayer()

    private var pinnedEvents = ExecutionFrontier<AtomicThreadEvent>(this.nThreads)

    private val delayedConsistencyCheckBuffer = mutableListOf<AtomicThreadEvent>()

    var detectedInconsistency: Inconsistency? = null
        private set

    private val sequentialConsistencyChecker =
        IncrementalSequentialConsistencyChecker(
            checkReleaseAcquireConsistency = true,
            approximateSequentialConsistency = false
        )

    private val atomicityChecker = AtomicityConsistencyChecker()

    private val consistencyChecker = aggregateConsistencyCheckers(
        listOf<IncrementalConsistencyChecker<AtomicThreadEvent, *>>(
            atomicityChecker,
            sequentialConsistencyChecker
        ),
        listOf(),
    )

    // TODO: move to EventIndexer once it will be implemented
    private data class ObjectEntry(
        val id: ObjectID,
        val obj: OpaqueValue,
        val event: AtomicThreadEvent,
    ) {
        init {
            require(id != NULL_OBJECT_ID)
            require(event.label is InitializationLabel || event.label is ObjectAllocationLabel)
        }

        val isExternal: Boolean
            get() = (event.label is InitializationLabel)

        var isLocal: Boolean =
            (id != STATIC_OBJECT_ID)

        val localThreadID: ThreadID
            get() = if (isLocal) event.threadId else -1
    }

    private val objectIdIndex = HashMap<ObjectID, ObjectEntry>()
    private val objectIndex = IdentityHashMap<Any, ObjectEntry>()

    private var nextObjectID = 1 + NULL_OBJECT_ID.id

    private val localWrites = mutableMapOf<MemoryLocation, AtomicThreadEvent>()

    /*
     * Map from blocked dangling events to their responses.
     * If event is blocked but the corresponding response has not yet arrived then it is mapped to null.
     */
    private val danglingEvents = mutableMapOf<AtomicThreadEvent, AtomicThreadEvent?>()

    init {
        root = addRootEvent()
    }

    fun startNextExploration(): Boolean {
        loop@while (true) {
            val event = rollbackToEvent { !it.visited }
                ?: return false
            event.visit()
            resetExploration(event)
            return true
        }
    }

    fun initializeExploration() {
        playedFrontier = MutableExecutionFrontier(nThreads)
        playedFrontier[initThreadId] = currentExecution[initThreadId]!!.last()
        replayer.currentEvent.ensure {
            it != null && it.label is InitializationLabel
        }
        replayer.setNextEvent()
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
                    if (responseEvent.dependencies.any { (it as ThreadEvent) !in playedFrontier }) {
                        _currentExecution.cut(responseEvent)
                        continue
                    }
                    check(responseEvent.label.isResponse)
                    _currentExecution.cutNext(responseEvent)
                }
                // otherwise just cut last replayed event
                else -> {
                    _currentExecution.cutNext(lastEvent)
                }
            }
        }
    }

    private fun rollbackToEvent(predicate: (BacktrackableEvent) -> Boolean): BacktrackableEvent? {
        val eventIdx = _events.indexOfLast(predicate)
        _events.subList(eventIdx + 1, _events.size).clear()
        return _events.lastOrNull()
    }

    private fun resetExploration(event: BacktrackableEvent) {
        check(event.label is InitializationLabel || event.label.isResponse)
        // reset consistency check state
        detectedInconsistency = null
        // reset dangling events
        danglingEvents.clear()
        // set current exploration root
        currentExplorationRoot = event
        // reset current execution
        _currentExecution = event.frontier.toMutableExecution()
        // copy pinned events and pin current re-exploration root event
        val pinnedEvents = event.pinnedEvents.copy()
            .apply { set(event.threadId, event) }
        // reset the internal state of incremental checkers
        consistencyChecker.reset(currentExecution)
        // add new event to current execution
        _currentExecution.add(event)
        // check new event with the incremental consistency checkers
        checkConsistencyIncrementally(event)
        // do the same for blocked requests
        for (blockedRequest in event.blockedRequests) {
            _currentExecution.add(blockedRequest)
            checkConsistencyIncrementally(blockedRequest)
            // additionally, pin blocked requests if all their predecessors are also blocked ...
            if (blockedRequest.parent == pinnedEvents[blockedRequest.threadId]) {
                pinnedEvents[blockedRequest.threadId] = blockedRequest
            }
            // ... and mark it as dangling
            markBlockedDanglingRequest(blockedRequest)
        }
        // set pinned events
        this.pinnedEvents = pinnedEvents.ensure {
            currentExecution.containsAll(it.events)
        }
        // check the full consistency of the whole execution
        checkConsistency()
        // set the replayer state
        replayer = Replayer(sequentialConsistencyChecker.executionOrder)
        // reset object indices --- retain only external events
        objectIdIndex.values.retainAll { it.isExternal }
        objectIndex.values.retainAll { it.isExternal }
        // reset state of other auxiliary structures
        delayedConsistencyCheckBuffer.clear()
        localWrites.clear()
    }

    fun checkConsistency(): Inconsistency? {
        // TODO: set suddenInvocationResult instead of `detectedInconsistency`
        if (detectedInconsistency == null) {
            detectedInconsistency = (consistencyChecker.check() as? Inconsistency)
        }
        return detectedInconsistency
    }

    private fun checkConsistencyIncrementally(event: AtomicThreadEvent): Inconsistency? {
        // TODO: set suddenInvocationResult instead of `detectedInconsistency`
        if (detectedInconsistency == null) {
            detectedInconsistency = (consistencyChecker.check(event) as? Inconsistency)
        }
        return detectedInconsistency
    }

    private class Replayer(private val executionOrder: List<ThreadEvent>) {
        private var index: Int = 0
        private val size: Int = executionOrder.size

        constructor(): this(listOf())

        fun inProgress(): Boolean =
            (index < size)

        val currentEvent: BacktrackableEvent?
            get() = if (inProgress()) (executionOrder[index] as? BacktrackableEvent) else null

        fun setNextEvent() {
            index++
        }
    }

    fun inReplayPhase(): Boolean =
        replayer.inProgress()

    fun inReplayPhase(iThread: Int): Boolean {
        val frontEvent = playedFrontier[iThread]
            ?.ensure { it in _currentExecution }
        return (frontEvent != currentExecution.lastEvent(iThread))
    }

    // should only be called in replay phase!
    fun canReplayNextEvent(iThread: Int): Boolean {
        return iThread == replayer.currentEvent?.threadId
    }

    private fun tryReplayEvent(iThread: Int): BacktrackableEvent? {
        if (inReplayPhase() && !canReplayNextEvent(iThread)) {
            // TODO: can we get rid of this?
            //   we can try to enforce more ordering invariants by grouping "atomic" events
            //   and also grouping events for which there is no reason to make switch in-between
            //   (e.g. `Alloc` followed by a `Write`).
            do {
                internalThreadSwitchCallback(iThread, SwitchReason.STRATEGY_SWITCH)
            } while (inReplayPhase() && !canReplayNextEvent(iThread))
        }
        return replayer.currentEvent
            ?.also { replayer.setNextEvent() }
    }

    // TODO: use calculateFrontier
    private fun VectorClock.toMutableFrontier(): MutableExecutionFrontier<AtomicThreadEvent> =
        (0 until nThreads).map { tid ->
            tid to currentExecution[tid, this[tid]]
        }.let {
            mutableExecutionFrontierOf(*it.toTypedArray())
        }

    private inner class BacktrackableEvent(
        label: EventLabel,
        parent: AtomicThreadEvent?,
        senders: List<AtomicThreadEvent> = listOf(),
        allocation: AtomicThreadEvent? = null,
        source: AtomicThreadEvent? = null,
        conflicts: List<AtomicThreadEvent> = listOf(),
        /**
         * State of the execution frontier at the point when event is created.
         */
        val frontier: ExecutionFrontier<AtomicThreadEvent>,
        /**
         * Frontier of pinned events.
         * Pinned events are the events that should not be
         * considered for branching in an exploration starting from this event.
         */
        pinnedEvents: ExecutionFrontier<AtomicThreadEvent>,
        /**
         * List of blocked request events.
         */
        val blockedRequests: List<AtomicThreadEvent>,
    ) : AbstractAtomicThreadEvent(
        label = label,
        // TODO: get rid of cast
        parent = (parent as? AbstractAtomicThreadEvent?),
        senders = senders,
        allocation = allocation,
        source = source,
        dependencies = listOfNotNull(allocation, source) + senders,
    ) {

        init {
            validate()
        }

        val pinnedEvents : ExecutionFrontier<AtomicThreadEvent> = pinnedEvents.copy().apply {
            // TODO: can reorder cut and merge?
            cut(conflicts)
            merge(causalityClock.toMutableFrontier())
            cutDanglingRequestEvents()
            set(threadId, parent)
        }

        var visited: Boolean = false
            private set

        fun visit() {
            visited = true
        }

    }

    private fun createEvent(
        iThread: Int,
        label: EventLabel,
        parent: AtomicThreadEvent?,
        dependencies: List<AtomicThreadEvent>,
        conflicts: List<AtomicThreadEvent>
    ): BacktrackableEvent? {
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
        val allocation = objectIdIndex[label.objID]?.event
        val source = (label as? WriteAccessLabel)?.writeValue?.let {
            objectIdIndex[it]?.event
        }
        val frontier = currentExecution.toMutableFrontier().apply {
            cut(conflicts)
            // for already unblocked dangling requests,
            // also put their responses into the frontier
            // TODO: extract this into function
            for ((request, response) in danglingEvents) {
                if (request in conflicts || response in conflicts)
                    continue
                if (request == this[request.threadId] && response != null &&
                    response.dependencies.all { it in this }) {
                    this.update(response)
                }
            }
        }
        val blockedRequests = frontier.cutDanglingRequestEvents()
            // TODO: perhaps, we should change this to the list of requests to conflicting response events?
            .filter { it.label.isBlocking && it != parent && (it.label !is CoroutineSuspendLabel) }
        frontier[iThread] = parent
        return BacktrackableEvent(
            label = label,
            parent = parent,
            senders = dependencies,
            allocation = allocation,
            source = source,
            conflicts = conflicts,
            frontier = frontier,
            pinnedEvents = pinnedEvents,
            blockedRequests = blockedRequests,
        )
    }

    private fun addEvent(
        iThread: Int,
        label: EventLabel,
        parent: AtomicThreadEvent?,
        dependencies: List<AtomicThreadEvent>
    ): BacktrackableEvent? {
        // TODO: do we really need this invariant?
        // require(parent !in dependencies)
        val conflicts = conflictingEvents(iThread, label, parent, dependencies)
        return createEvent(iThread, label, parent, dependencies, conflicts)?.also { event ->
            _events.add(event)
        }
    }

    private fun conflictingEvents(
        iThread: Int,
        label: EventLabel,
        parent: AtomicThreadEvent?,
        dependencies: List<AtomicThreadEvent>
    ): List<AtomicThreadEvent> {
        val position = parent?.let { it.threadPosition + 1 } ?: 0
        val conflicts = mutableListOf<AtomicThreadEvent>()
        // if the current execution already has an event in given position --- then it is conflict
        currentExecution[iThread, position]?.also { conflicts.add(it) }
        // handle label specific cases
        // TODO: unify this logic for various kinds of labels?
        when {
            // lock-response synchronizing with our unlock is conflict
            label is LockLabel && label.isResponse && !label.isReentry -> run {
                val unlock = dependencies.first { it.label.asUnlockLabel(label.mutex) != null }
                currentExecution.forEach { event ->
                    if (event.label.satisfies<LockLabel> { isResponse && mutex == label.mutex }
                        && event.locksFrom == unlock) {
                        conflicts.add(event)
                    }
                }
            }
            // wait-response synchronizing with our notify is conflict
            label is WaitLabel && label.isResponse -> run {
                val notify = dependencies.first { it.label is NotifyLabel }
                if ((notify.label as NotifyLabel).isBroadcast)
                    return@run
                currentExecution.forEach { event ->
                    if (event.label.satisfies<WaitLabel> { isResponse && mutex == label.mutex }
                        && event.notifiedBy == notify) {
                        conflicts.add(event)
                    }
                }
            }
            // TODO: add similar rule for read-exclusive-response?
        }
        return conflicts
    }

    private fun addEventToCurrentExecution(event: BacktrackableEvent, visit: Boolean = true) {
        // Mark event as visited if necessary.
        if (visit) {
            event.visit()
        }
        // Check if the added event is replayed event.
        val isReplayedEvent = inReplayPhase(event.threadId)
        // Update current execution and replayed frontier.
        if (!isReplayedEvent) {
            _currentExecution.add(event)
        }
        playedFrontier.update(event)
        // Unmark dangling request if its response was added.
        if (event.label.isResponse && event.label.isBlocking && event.parent in danglingEvents) {
            unmarkBlockedDanglingRequest(event.parent!!)
        }
        // mark the object as non-local if it is accessed from another thread
        if (event.label.objID != NULL_OBJECT_ID && event.label.objID != STATIC_OBJECT_ID) {
            val objEntry = objectIdIndex[event.label.objID]!!
            objEntry.isLocal = (objEntry.localThreadID == event.threadId)
            // update latest local write index for a write event
            if (objEntry.isLocal && event.label is WriteAccessLabel) {
                localWrites[event.label.location] = event
            }
        }
        // If we are still in replay phase, but the added event is not a replayed event,
        // then save it to delayed events buffer to postpone its further processing.
        if (inReplayPhase()) {
            if (!isReplayedEvent) {
                delayedConsistencyCheckBuffer.add(event)
            }
            return
        }
        // If we are not in replay phase anymore, but the current event is replayed event,
        // it means that we just finished replay phase (i.e. the given event is the last replayed event).
        // In this case, we need to proceed all postponed non-replayed events.
        if (isReplayedEvent) {
            for (delayedEvent in delayedConsistencyCheckBuffer) {
                if (delayedEvent.label.isSend) {
                    addSynchronizedEvents(delayedEvent)
                }
                checkConsistencyIncrementally(delayedEvent)
            }
            delayedConsistencyCheckBuffer.clear()
            return
        }
        // If we are not in the replay phase and the newly added event is not replayed, then we proceed it as usual.
        if (event.label.isSend) {
            addSynchronizedEvents(event)
        }
        val inconsistency = checkConsistencyIncrementally(event)
        if (inconsistency != null) {
            reportInconsistencyCallback(inconsistency)
        }
    }

    fun getValue(id: ValueID): OpaqueValue? = when (id) {
        NULL_OBJECT_ID -> null
        is PrimitiveID -> id.value.opaque()
        is ObjectID -> objectIdIndex[id]?.obj
    }

    fun getValueID(value: OpaqueValue?): ValueID {
        if (value == null)
            return NULL_OBJECT_ID
        if (value.isPrimitive)
            return PrimitiveID(value.unwrap())
        return objectIndex[value.unwrap()]?.id ?: INVALID_OBJECT_ID
    }

    fun computeValueID(value: OpaqueValue?): ValueID {
        if (value == null)
            return NULL_OBJECT_ID
        if (value.isPrimitive)
            return PrimitiveID(value.unwrap())
        objectIndex[value.unwrap()]?.let {
            return it.id
        }
        val id = ObjectID(nextObjectID++)
        val entry = ObjectEntry(id, value, root)
        val initLabel = (root.label as InitializationLabel)
        val className = value.unwrap().javaClass.simpleName
        registerObjectEntry(entry)
        initLabel.trackExternalObject(className, id)
        return entry.id
    }

    private fun registerObjectEntry(entry: ObjectEntry) {
        objectIdIndex.put(entry.id, entry).ensureNull()
        objectIndex.put(entry.obj.unwrap(), entry).ensureNull()
    }

    fun allocationEvent(id: ObjectID): AtomicThreadEvent? {
        return objectIdIndex[id]?.event
    }

    private val EventLabel.syncType
        get() = syncAlgebra.syncType(this)

    private fun EventLabel.synchronizable(other: EventLabel) =
        syncAlgebra.synchronizable(this, other)

    private fun EventLabel.synchronize(other: EventLabel) =
        syncAlgebra.synchronize(this, other)

    private fun synchronizationCandidates(event: AtomicThreadEvent): List<AtomicThreadEvent> {
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
        val label = event.label
        return when {
            // for read-request events we search for the last write to the same memory location
            // in the same thread, and then filter out all causal predecessors of this last write,
            // because these events are "obsolete" --- reading from them will result in coherence cycle
            // and will violate consistency
            label is ReadAccessLabel && label.isRequest -> {
                // val threadLastWrite = currentExecution[event.threadId]?.lastOrNull {
                //     it.label is WriteAccessLabel && it.label.location == event.label.location
                // } ?: root
                if (label.objID != STATIC_OBJECT_ID) {
                    val objectEntry = objectIdIndex[label.objID]!!
                    if (objectEntry.isLocal) {
                        val write = localWrites.computeIfAbsent(label.location) { objectEntry.event }
                        return listOf(write)
                    }
                }
                val threadReads = currentExecution[event.threadId]!!.filter {
                    it.label.isResponse && (it.label as? ReadAccessLabel)?.location == label.location
                }
                val lastSeenWrite = threadReads.lastOrNull()?.let { (it as AbstractAtomicThreadEvent).readsFrom }
                val staleWrites = threadReads
                    .map { (it as AbstractAtomicThreadEvent).readsFrom }
                    .filter { it != lastSeenWrite }
                    .distinct()
                val racyWrites = calculateRacyWrites(label.location, event.causalityClock.toMutableFrontier())
                candidates.filter {
                    // !causalityOrder.lessThan(it, threadLastWrite) &&
                    !racyWrites.any { write -> causalityOrder.lessThan(it, write) } &&
                    !staleWrites.any { write -> causalityOrder.lessOrEqual(it, write) }
                }
            }
            label is WriteAccessLabel -> {
                if (label.objID != STATIC_OBJECT_ID) {
                    val objectEntry = objectIdIndex[label.objID]!!
                    if (objectEntry.isLocal) {
                        return listOf()
                    }
                }
                candidates
            }
            label is ObjectAllocationLabel -> {
                return listOf()
            }
            // re-entry lock-request synchronizes only with object allocation label
            label is LockLabel && event.label.isRequest && label.isReentry -> {
                candidates.filter { it.label.asObjectAllocationLabel(label.mutex) != null }
            }
            // re-entry unlock synchronizes with nothing
            label is UnlockLabel && label.isReentry -> {
                return listOf(root)
            }
            label is CoroutineSuspendLabel && label.isRequest -> {
                // filter-out InitializationLabel to prevent creating cancellation response
                // TODO: refactor!!!
                candidates.filter { it.label !is InitializationLabel }
            }
            // label is CoroutineResumeLabel -> {
            //     val suspendRequest = getBlockedAwaitingRequest(label.threadId).ensure { request ->
            //         (request != null) && request.label is CoroutineSuspendLabel
            //                 && ((request.label as CoroutineSuspendLabel).actorId == label.actorId)
            //     }!!
            //     return listOf(suspendRequest)
            // }
            label is RandomLabel ->
                return listOf()

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
    private fun addSynchronizedEvents(event: AtomicThreadEvent): List<AtomicThreadEvent> {
        // TODO: we should maintain an index of read/write accesses to specific memory location
        val syncEvents = when (event.label.syncType) {
            SynchronizationType.Binary -> addBinarySynchronizedEvents(event, synchronizationCandidates(event))
            SynchronizationType.Barrier -> addBarrierSynchronizedEvents(event, synchronizationCandidates(event))
            else -> return listOf()
        }
        // if there are responses to blocked dangling requests, then set the response of one of these requests
        for (syncEvent in syncEvents) {
            val requestEvent = (syncEvent.parent as? AtomicThreadEvent)
                ?.takeIf { it.label.isRequest && it.label.isBlocking }
                ?: continue
            if (requestEvent in danglingEvents && getUnblockingResponse(requestEvent) == null) {
                setUnblockingResponse(syncEvent)
                break
            }
        }
        return syncEvents
    }

    private fun addBinarySynchronizedEvents(
        event: AtomicThreadEvent,
        candidates: Collection<AtomicThreadEvent>
    ): List<AtomicThreadEvent> {
        require(event.label.syncType == SynchronizationType.Binary)
        // TODO: sort resulting events according to some strategy?
        return candidates
            .mapNotNull { other ->
                val syncLab = event.label.synchronize(other.label)
                    ?: return@mapNotNull null
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

    private fun addBarrierSynchronizedEvents(
        event: AtomicThreadEvent,
        candidates: Collection<AtomicThreadEvent>
    ): List<AtomicThreadEvent> {
        require(event.label.syncType == SynchronizationType.Barrier)
        val (syncLab, dependencies) =
            candidates.fold(event.label to listOf(event)) { (lab, deps), candidateEvent ->
                candidateEvent.label.synchronize(lab)?.let {
                    (it to deps + candidateEvent)
                } ?: (lab to deps)
            }
        if (syncLab.isBlocking && !syncLab.unblocked)
            return listOf()
        // We assume that at most, one of the events participating into synchronization
        // is a request event, and the result of synchronization is a response event.
        check(syncLab.isResponse)
        val parent = dependencies.first { it.label.isRequest }
        val responseEvent = addEvent(parent.threadId, syncLab, parent, dependencies.filter { it != parent })
        return listOfNotNull(responseEvent)
    }

    private fun addRootEvent(): AtomicThreadEvent {
        // we do not mark root event as visited purposefully;
        // this is just a trick to make the first call to `startNextExploration`
        // to pick the root event as the next event to explore from.
        val label = InitializationLabel(initThreadId, mainThreadId) { location ->
            val value = memoryInitializer(location)
            computeValueID(value)
        }
        return addEvent(initThreadId, label, parent = null, dependencies = emptyList())!!.also {
            addEventToCurrentExecution(it, visit = false)
        }
    }

    private fun addSendEvent(iThread: Int, label: EventLabel, dependencies: List<AtomicThreadEvent> = listOf()): AtomicThreadEvent {
        require(label.isSend)
        tryReplayEvent(iThread)?.let { event ->
            check(event.label == label)
            addEventToCurrentExecution(event)
            return event
        }
        val parent = playedFrontier[iThread]
        return addEvent(iThread, label, parent, dependencies)!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    private fun addRequestEvent(iThread: Int, label: EventLabel): AtomicThreadEvent {
        require(label.isRequest)
        tryReplayEvent(iThread)?.let { event ->
            check(event.label == label)
            addEventToCurrentExecution(event)
            return event
        }
        val parent = playedFrontier[iThread]
        return addEvent(iThread, label, parent, dependencies = emptyList())!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    private fun addResponseEvents(requestEvent: AtomicThreadEvent): Pair<AtomicThreadEvent?, List<AtomicThreadEvent>> {
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
            check(event.label == event.resynchronize(syncAlgebra))
            addEventToCurrentExecution(event)
            return event to listOf(event)
        }
        if (requestEvent.label.isBlocking && requestEvent in danglingEvents) {
            val event = getUnblockingResponse(requestEvent)
                ?: return (null to listOf())
            check(event.label.isResponse)
            check(event.parent == requestEvent)
            check(event is BacktrackableEvent)
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
            addEventToCurrentExecution(event as BacktrackableEvent)
        }
        return (chosenEvent to responseEvents)
    }

    private fun addActorEvent(iThread: Int, label: ActorLabel): AtomicThreadEvent {
        tryReplayEvent(iThread)?.let { event ->
            check(event.label == label)
            addEventToCurrentExecution(event)
            return event
        }
        val parent = playedFrontier[iThread]
        return addEvent(iThread, label, parent, dependencies = emptyList())!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    fun isBlockedRequest(request: AtomicThreadEvent): Boolean {
        require(request.label.isRequest && request.label.isBlocking)
        return (request == playedFrontier[request.threadId])
    }

    fun isBlockedDanglingRequest(request: AtomicThreadEvent): Boolean {
        require(request.label.isRequest && request.label.isBlocking)
        return currentExecution.isBlockedDanglingRequest(request)
    }

    fun isBlockedAwaitingRequest(request: AtomicThreadEvent): Boolean {
        require(isBlockedRequest(request))
        if (inReplayPhase(request.threadId)) {
            return !canReplayNextEvent(request.threadId)
        }
        if (request in danglingEvents) {
            return danglingEvents[request] == null
        }
        return false
    }

    fun getBlockedRequest(iThread: Int): AtomicThreadEvent? =
        playedFrontier[iThread]?.takeIf { it.label.isRequest && it.label.isBlocking }

    fun getBlockedAwaitingRequest(iThread: Int): AtomicThreadEvent? =
        getBlockedRequest(iThread)?.takeIf { isBlockedAwaitingRequest(it) }

    private fun markBlockedDanglingRequest(request: AtomicThreadEvent) {
        require(isBlockedDanglingRequest(request))
        check(request !in danglingEvents)
        check(danglingEvents.keys.all { it.threadId != request.threadId })
        danglingEvents.put(request, null).ensureNull()
    }

    private fun unmarkBlockedDanglingRequest(request: AtomicThreadEvent) {
        require(request.label.isRequest && request.label.isBlocking)
        require(!isBlockedDanglingRequest(request))
        require(request in danglingEvents)
        danglingEvents.remove(request)
    }

    private fun setUnblockingResponse(response: AtomicThreadEvent) {
        require(response.label.isResponse && response.label.isBlocking)
        val request = response.parent
            .ensure { it != null }
            .ensure { isBlockedDanglingRequest(it!!) }
            .ensure { it in danglingEvents }
        danglingEvents.put(request!!, response).ensureNull()
    }

    private fun getUnblockingResponse(request: AtomicThreadEvent): AtomicThreadEvent? {
        require(isBlockedDanglingRequest(request))
        require(request in danglingEvents)
        return danglingEvents[request]
    }

    fun addThreadStartEvent(iThread: Int): AtomicThreadEvent {
        val label = ThreadStartLabel(
            threadId = iThread,
            kind = LabelKind.Request,
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, responseEvents) = addResponseEvents(requestEvent)
        checkNotNull(responseEvent)
        check(responseEvents.size == 1)
        return responseEvent
    }

    fun addThreadFinishEvent(iThread: Int): AtomicThreadEvent {
        val label = ThreadFinishLabel(
            threadId = iThread,
        )
        return addSendEvent(iThread, label)
    }

    fun addThreadForkEvent(iThread: Int, forkThreadIds: Set<Int>): AtomicThreadEvent {
        val label = ThreadForkLabel(
            forkThreadIds = forkThreadIds
        )
        return addSendEvent(iThread, label)
    }

    fun addThreadJoinEvent(iThread: Int, joinThreadIds: Set<Int>): AtomicThreadEvent {
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

    fun addObjectAllocationEvent(iThread: Int, value: OpaqueValue): AtomicThreadEvent {
        tryReplayEvent(iThread)?.let { event ->
            val id = event.label.objID
            val entry = ObjectEntry(id, value, event)
            registerObjectEntry(entry)
            addEventToCurrentExecution(event)
            return event
        }
        val id = ObjectID(nextObjectID++)
        val label = ObjectAllocationLabel(
            objID = id,
            className = value.unwrap().javaClass.simpleName,
            memoryInitializer = { location ->
                val initValue = memoryInitializer(location)
                computeValueID(initValue)
            },
        )
        val parent = playedFrontier[iThread]
        val dependencies = listOf<AtomicThreadEvent>()
        return addEvent(iThread, label, parent, dependencies)!!.also { event ->
            val entry = ObjectEntry(id, value, event)
            registerObjectEntry(entry)
            addEventToCurrentExecution(event)
        }
    }

    fun addWriteEvent(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?,
                      isExclusive: Boolean = false): AtomicThreadEvent {
        val label = WriteAccessLabel(
            location = location,
            value = computeValueID(value),
            kClass = kClass,
            isExclusive = isExclusive,
            codeLocation = codeLocation,
        )
        return addSendEvent(iThread, label)
    }

    fun addReadEvent(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>,
                     isExclusive: Boolean = false): AtomicThreadEvent {
        // we first create read-request event with unknown (null) value,
        // value will be filled later in read-response event
        val label = ReadAccessLabel(
            kind = LabelKind.Request,
            location = location,
            value = NULL_OBJECT_ID,
            kClass = kClass,
            isExclusive = isExclusive,
            codeLocation = codeLocation
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, _) = addResponseEvents(requestEvent)
        // TODO: think again --- is it possible that there is no write to read-from?
        //  Probably not, because in Kotlin variables are always initialized by default?
        //  What about initialization-related issues?
        checkNotNull(responseEvent)
        if (isSpinLoopBoundReached(responseEvent)) {
            internalThreadSwitchCallback(iThread, SwitchReason.SPIN_BOUND)
        }
        return responseEvent
    }

    fun addLockRequestEvent(iThread: Int, mutex: OpaqueValue, reentranceDepth: Int = 1, reentranceCount: Int = 1, isWaitLock: Boolean = false): AtomicThreadEvent {
        val label = LockLabel(
            kind = LabelKind.Request,
            mutex = computeValueID(mutex) as ObjectID,
            reentranceDepth = reentranceDepth,
            reentranceCount = reentranceCount,
            isWaitLock = isWaitLock,
        )
        return addRequestEvent(iThread, label)
    }

    fun addLockResponseEvent(lockRequest: AtomicThreadEvent): AtomicThreadEvent? {
        require(lockRequest.label.isRequest && lockRequest.label is LockLabel)
        return addResponseEvents(lockRequest).first
    }

    fun addUnlockEvent(iThread: Int, mutex: OpaqueValue, reentranceDepth: Int = 1, reentranceCount: Int = 1, isWaitUnlock: Boolean = false): AtomicThreadEvent {
        val label = UnlockLabel(
            mutex = computeValueID(mutex) as ObjectID,
            reentranceDepth = reentranceDepth,
            reentranceCount = reentranceCount,
            isWaitUnlock = isWaitUnlock,
        )
        return addSendEvent(iThread, label)
    }

    fun addWaitRequestEvent(iThread: Int, mutex: OpaqueValue): AtomicThreadEvent {
        val label = WaitLabel(
            kind = LabelKind.Request,
            mutex = computeValueID(mutex) as ObjectID,
        )
        return addRequestEvent(iThread, label)

    }

    fun addWaitResponseEvent(waitRequest: AtomicThreadEvent): AtomicThreadEvent? {
        require(waitRequest.label.isRequest && waitRequest.label is WaitLabel)
        return addResponseEvents(waitRequest).first
    }

    fun addNotifyEvent(iThread: Int, mutex: OpaqueValue, isBroadcast: Boolean): AtomicThreadEvent {
        // TODO: we currently ignore isBroadcast flag and handle `notify` similarly as `notifyAll`.
        //   It is correct wrt. Java's semantics, since `wait` can wake-up spuriously according to the spec.
        //   Thus multiple wake-ups due to single notify can be interpreted as spurious.
        //   However, if one day we will want to support wait semantics without spurious wake-ups
        //   we will need to revisit this.
        val label = NotifyLabel(
            mutex = computeValueID(mutex) as ObjectID,
            isBroadcast = isBroadcast
        )
        return addSendEvent(iThread, label)
    }

    fun addParkRequestEvent(iThread: Int): AtomicThreadEvent {
        val label = ParkLabel(LabelKind.Request, iThread)
        return addRequestEvent(iThread, label)
    }

    fun addParkResponseEvent(parkRequest: AtomicThreadEvent): AtomicThreadEvent? {
        require(parkRequest.label.isRequest && parkRequest.label is ParkLabel)
        return addResponseEvents(parkRequest).first
    }

    fun addUnparkEvent(iThread: Int, unparkingThreadId: Int): AtomicThreadEvent {
        val label = UnparkLabel(unparkingThreadId)
        return addSendEvent(iThread, label)
    }

    fun addCoroutineSuspendRequestEvent(iThread: Int, iActor: Int, promptCancellation: Boolean = false): AtomicThreadEvent {
        val label = CoroutineSuspendLabel(LabelKind.Request, iThread, iActor, promptCancellation = promptCancellation)
        return addRequestEvent(iThread, label)
    }

    fun addCoroutineSuspendResponseEvent(iThread: Int, iActor: Int): AtomicThreadEvent {
        val request = getBlockedRequest(iThread).ensure { event ->
            (event != null) && event.label.satisfies<CoroutineSuspendLabel> { actorId == iActor }
        }!!
        val (response, events) = addResponseEvents(request)
        check(events.size == 1)
        return response!!
    }

    fun addCoroutineCancelResponseEvent(iThread: Int, iActor: Int): AtomicThreadEvent {
        val request = getBlockedRequest(iThread).ensure { event ->
            (event != null) && event.label.satisfies<CoroutineSuspendLabel> { actorId == iActor }
        }!!
        val label = (request.label as CoroutineSuspendLabel).getResponse(root.label)!!
        tryReplayEvent(iThread)?.let { event ->
            check(event.label == label)
            addEventToCurrentExecution(event)
            return event
        }
        return addEvent(iThread, label, parent = request, dependencies = listOf(root))!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    fun addCoroutineResumeEvent(iThread: Int, iResumedThread: Int, iResumedActor: Int): AtomicThreadEvent {
        val label = CoroutineResumeLabel(iResumedThread, iResumedActor)
        return addSendEvent(iThread, label)
    }

    fun addActorStartEvent(iThread: Int, actor: Actor): AtomicThreadEvent {
        val label = ActorLabel(iThread, ActorLabelKind.Start, actor)
        return addActorEvent(iThread, label)
    }

    fun addActorEndEvent(iThread: Int, actor: Actor): AtomicThreadEvent {
        val label = ActorLabel(iThread, ActorLabelKind.End, actor)
        return addActorEvent(iThread, label)
    }

    fun tryReplayRandomEvent(iThread: Int): AtomicThreadEvent? {
        tryReplayEvent(iThread)?.let { event ->
            check(event.label is RandomLabel)
            addEventToCurrentExecution(event)
            return event
        }
        return null
    }

    fun addRandomEvent(iThread: Int, generated: Int): AtomicThreadEvent {
        val label = RandomLabel(generated)
        val parent = playedFrontier[iThread]
        return addEvent(iThread, label, parent, dependencies = emptyList())!!.also { event ->
            addEventToCurrentExecution(event)
        }
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
    fun calculateMemoryLocationView(
        location: MemoryLocation,
        observation: ExecutionFrontier<AtomicThreadEvent>
    ): ExecutionFrontier<AtomicThreadEvent> =
        observation.threadMap.map { (tid, event) ->
            val lastWrite = event
                ?.ensure { it in currentExecution }
                ?.pred(inclusive = true) {
                    it.label.asMemoryAccessLabel(location)?.takeIf { label -> label.isWrite } != null
                }
            (tid to lastWrite as? AtomicThreadEvent?)
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
    fun calculateRacyWrites(
        location: MemoryLocation,
        observation: ExecutionFrontier<AtomicThreadEvent>)
    : List<ThreadEvent> {
        val writes = calculateMemoryLocationView(location, observation).events
        return writes.filter { write ->
            !writes.any { other ->
                causalityOrder.lessThan(write, other)
            }
        }
    }

    private fun isSpinLoopBoundReached(event: ThreadEvent): Boolean {
        check(event.label is ReadAccessLabel && event.label.isResponse)
        val readLabel = (event.label as ReadAccessLabel)
        val location = readLabel.location
        val readValue = readLabel.readValue
        val codeLocation = readLabel.codeLocation
        // a potential spin-loop occurs when we have visited the same code location more than N times
        if (loopDetector.codeLocationCounter(event.threadId, codeLocation) < SPIN_BOUND)
            return false
        // if the last 3 reads with the same code location read the same value,
        // then we consider this a spin-loop
        var spinEvent: ThreadEvent = event
        var spinCounter = SPIN_BOUND
        while (spinCounter-- > 0) {
            spinEvent = spinEvent.pred {
                it.label.isResponse && it.label.satisfies<ReadAccessLabel> {
                    this.location == location && this.codeLocation == codeLocation
                }
            } ?: return false
            if ((spinEvent.label as ReadAccessLabel).readValue != readValue)
                return false
        }
        return true
    }

}