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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.*
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.ensure


class EventStructure(
    nParallelThreads: Int,
    val memoryInitializer: MemoryInitializer,
    // TODO: refactor --- avoid using callbacks!
    private val reportInconsistencyCallback: ReportInconsistencyCallback,
    private val internalThreadSwitchCallback: InternalThreadSwitchCallback,
) {
    val mainThreadId = 0
    val initThreadId = nParallelThreads
    val maxThreadId = initThreadId
    val nThreads = maxThreadId + 1

    /**
     * Mutable list of the event structure events.
     */
    private val _events = sortedMutableListOf<AtomicThreadEvent>()

    /**
     * List of the event structure events.
     */
    val events: SortedList<AtomicThreadEvent> = _events

    /**
     * Root event of the whole event structure.
     * Its label is [InitializationLabel].
     */
    @SuppressWarnings("WeakerAccess")
    val root: AtomicThreadEvent

    /**
     * The root event of the currently being explored execution.
     * In other words, it is a choice point that has led to the current exploration.
     *
     * The label of this event should be of [LabelKind.Response] kind.
     */
    @SuppressWarnings("WeakerAccess")
    lateinit var currentExplorationRoot: Event
        private set

    /**
     * Mutable list of backtracking points.
     */
    private val backtrackingPoints = sortedMutableListOf<BacktrackingPoint>()


    /**
     * The mutable execution currently being explored.
     */
    private var _execution = MutableExtendedExecution(this.nThreads)

    /**
     * The execution currently being explored.
     */
    val execution: ExtendedExecution
        get() = _execution

    /**
     * The frontier representing an already replayed part of the execution currently being explored.
     */
    private var playedFrontier = MutableExecutionFrontier<AtomicThreadEvent>(this.nThreads)

    /**
     * An object managing the replay process of the execution currently being explored.
     */
    private var replayer = Replayer()

    /**
     * Synchronization algebra used for synchronization of events.
     */
    @SuppressWarnings("WeakerAccess")
    val syncAlgebra: SynchronizationAlgebra = AtomicSynchronizationAlgebra

    /**
     * The frontier encoding the subset of pinned events of the execution currently being explored.
     * Pinned events cannot be revisited and thus do not participate in the synchronization
     * with newly added events.
     */
    private var pinnedEvents = ExecutionFrontier<AtomicThreadEvent>(this.nThreads)

    /**
     * The object registry, storing information about all objects
     * created during the execution currently being explored.
     */
    val objectRegistry = ObjectRegistry()

    /**
     * For each blocked thread, stores a descriptor of the blocked event.
     *
     * The thread may become blocked when it issues a request-event
     * denoting some blocking operation (e.g., mutex lock),
     * such that the corresponding response-event cannot be created immediately
     * (for example, because the mutex is already acquired by another thread).
     *
     * Therefore, a blocked event is a composite event that may consist of either:
     *  - a blocked request event alone;
     *  - or a blocked request event followed by an unblocking response event.
     * In the latter case, we say that the event becomes effectively unblocked.
     * The unblocking response is typically created as a result
     * of synchronization with another event when it is added
     * to the currently explored [execution].
     *
     * However, at the point when the unblocking response is created
     * the unblocking response may not be added to
     * the currently explored [execution] immediately.
     * It is added later when the blocked thread is scheduled again.
     * At this point the current [BlockedEventDescriptor] is removed
     * from the [blockedEvents] mapping, and the thread becomes fully unblocked.
     */
    private val blockedEvents: MutableThreadMap<BlockedEventDescriptor> =
        ArrayIntMap(this.nThreads)

    private val delayedConsistencyCheckBuffer = mutableListOf<AtomicThreadEvent>()

    private val readCodeLocationsCounter = mutableMapOf<Pair<Int, Int>, Int>()

    init {
        root = addRootEvent()
        objectRegistry.initialize(root)
    }

    /* ************************************************************************* */
    /*      Exploration                                                          */
    /* ************************************************************************* */

    fun startNextExploration(): Boolean {
        loop@while (true) {
            val backtrackingPoint = rollbackTo { !it.visited }
                ?: return false
            backtrackingPoint.visit()
            resetExploration(backtrackingPoint)
            return true
        }
    }

    fun initializeExploration() {
        // reset re-played frontier
        playedFrontier = MutableExecutionFrontier(nThreads)
        playedFrontier[initThreadId] = execution[initThreadId]!!.last()
        // reset replayer state
        replayer.reset()
        if (replayer.inProgress()) {
            replayer.currentEvent.ensure {
                it != null && it.label is InitializationLabel
            }
            replayer.setNextEvent()
        }
        // reset object indices --- retain only external events
        objectRegistry.retain { it.isExternal }
        // reset state of other auxiliary structures
        delayedConsistencyCheckBuffer.clear()
        readCodeLocationsCounter.clear()
    }

    fun abortExploration() {
        // we abort the current exploration by resetting the current execution to its replayed part;
        // however, we need to handle blocking request in a special way --- we include their response part
        // to detect potential blocking response uniqueness violations
        // (e.g., two lock events unblocked by the same unlock event)
        for ((tid, event) in playedFrontier.threadMap.entries) {
            if (event == null)
                continue
            if (!(event.label.isRequest && event.label.isBlocking))
                continue
            val response = execution[tid, event.threadPosition + 1]
                ?: continue
            check(response.label.isResponse)
            // skip the response if it does not depend on any re-played event
            if (response.dependencies.any { it !in playedFrontier })
                continue
            playedFrontier.update(response)
        }
        _execution.reset(playedFrontier)
    }

    private fun rollbackTo(predicate: (BacktrackingPoint) -> Boolean): BacktrackingPoint? {
        val idx = backtrackingPoints.indexOfLast(predicate)
        val backtrackingPoint = backtrackingPoints.getOrNull(idx)
        val eventIdx = events.indexOfLast { it == backtrackingPoint?.event }
        backtrackingPoints.subList(idx + 1, backtrackingPoints.size).clear()
        _events.subList(eventIdx + 1, events.size).clear()
        return backtrackingPoint
    }

    private fun resetExploration(backtrackingPoint: BacktrackingPoint) {
        // get the event to backtrack to
        val event = backtrackingPoint.event.ensure {
            it.label is InitializationLabel || it.label.isResponse
        }
        // reset blocked events
        blockedEvents.clear()
        // set current exploration root
        currentExplorationRoot = event
        // reset current execution
        _execution.reset(backtrackingPoint.frontier)
        // copy pinned events and pin current re-exploration root event
        val pinnedEvents = backtrackingPoint.pinnedEvents.copy()
            .apply { set(event.threadId, event) }
        // add new event to current execution
        _execution.add(event)
        // do the same for blocked requests
        for (blockedRequest in backtrackingPoint.blockedRequests) {
            _execution.add(blockedRequest)
            // additionally, pin blocked requests if all their predecessors are also blocked ...
            if (blockedRequest.parent == pinnedEvents[blockedRequest.threadId]) {
                pinnedEvents[blockedRequest.threadId] = blockedRequest
            }
            // ... and also block them
            blockRequest(blockedRequest)
        }
        // set pinned events
        this.pinnedEvents = pinnedEvents.ensure {
            execution.containsAll(it.events)
        }
        // check consistency of the whole execution
        _execution.checkConsistency()
        // set the replayer state
        val replayOrdering = _execution.executionOrderComputable.value.ordering
        replayer = Replayer(replayOrdering)
    }

    fun checkConsistency(): Inconsistency? {
        // TODO: set suddenInvocationResult?
        return _execution.checkConsistency()
    }

    /* ************************************************************************* */
    /*      Event creation                                                       */
    /* ************************************************************************* */

    /**
     * Class representing a backtracking point in the exploration of the program's executions.
     *
     * @property event The event at which to start a new exploration.
     * @property frontier The execution frontier at the point when the event was created.
     * @property pinnedEvents The frontier of pinned events that should not be
     *   considered for exploration branching.
     * @property blockedRequests The list of blocked request events.
     * @property visited Flag to indicate if this backtracking point has been visited.
     */
    private class BacktrackingPoint(
        val event: AtomicThreadEvent,
        val frontier: ExecutionFrontier<AtomicThreadEvent>,
        val pinnedEvents: ExecutionFrontier<AtomicThreadEvent>,
        val blockedRequests: List<AtomicThreadEvent>,
    ) : Comparable<BacktrackingPoint> {

        var visited: Boolean = false
            private set

        fun visit() {
            visited = true
        }

        override fun compareTo(other: BacktrackingPoint): Int {
            return event.id.compareTo(other.event.id)
        }
    }

    private fun createBacktrackingPoint(event: AtomicThreadEvent, conflicts: List<AtomicThreadEvent>) {
        val frontier = execution.toMutableFrontier().apply {
            cut(conflicts)
            // for already unblocked dangling requests,
            // also put their responses into the frontier
            addUnblockingResponses(conflicts)
        }
        val danglingRequests = frontier.getDanglingRequests()
        val blockedRequests = danglingRequests
            // TODO: perhaps, we should change this to the list of requests to conflicting response events?
            .filter { it.label.isBlocking && it != event.parent && (it.label !is CoroutineSuspendLabel) }
        frontier.apply {
            cut(danglingRequests)
            set(event.threadId, event.parent)
        }
        val pinnedEvents = pinnedEvents.copy().apply {
            val causalityFrontier = execution.calculateFrontier(event.causalityClock)
            merge(causalityFrontier)
            cut(conflicts)
            cut(getDanglingRequests())
            cut(event)
        }
        val backtrackingPoint = BacktrackingPoint(
            event = event,
            frontier = frontier,
            pinnedEvents = pinnedEvents,
            blockedRequests = blockedRequests,
        )
        backtrackingPoints.add(backtrackingPoint)
    }

    private fun createEvent(
        iThread: Int,
        label: EventLabel,
        parent: AtomicThreadEvent?,
        dependencies: List<AtomicThreadEvent>,
        visit: Boolean = true,
    ): AtomicThreadEvent? {
        val conflicts = getConflictingEvents(iThread, label, parent, dependencies)
        if (isCausalityViolated(parent, dependencies, conflicts))
            return null
        val allocation = objectRegistry[label.objectID]?.allocation
        val source = (label as? WriteAccessLabel)?.writeValue?.let {
            objectRegistry[it]?.allocation
        }
        val event = AtomicThreadEventImpl(
            label = label,
            parent = parent,
            senders = dependencies,
            allocation = allocation,
            source = source,
            dependencies = listOfNotNull(allocation, source) + dependencies,
        )
        _events.add(event)
        // if the event is not visited immediately,
        // then we create a backtracking point to visit it later
        if (!visit) {
            createBacktrackingPoint(event, conflicts)
        }
        return event
    }

    private fun getConflictingEvents(
        iThread: Int,
        label: EventLabel,
        parent: AtomicThreadEvent?,
        dependencies: List<AtomicThreadEvent>
    ): List<AtomicThreadEvent> {
        val position = parent?.let { it.threadPosition + 1 } ?: 0
        val conflicts = mutableListOf<AtomicThreadEvent>()
        // if the current execution already has an event in given position --- then it is conflict
        execution[iThread, position]?.also { conflicts.add(it) }
        // handle label specific cases
        // TODO: unify this logic for various kinds of labels?
        when {
            // lock-response synchronizing with our unlock is conflict
            label is LockLabel && label.isResponse && !label.isReentry -> run {
                val unlock = dependencies.first { it.label.asUnlockLabel(label.mutexID) != null }
                execution.forEach { event ->
                    if (event.label.satisfies<LockLabel> { isResponse && mutexID == label.mutexID }
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
                execution.forEach { event ->
                    if (event.label.satisfies<WaitLabel> { isResponse && mutexID == label.mutexID }
                        && event.notifiedBy == notify) {
                        conflicts.add(event)
                    }
                }
            }
            // TODO: add similar rule for read-exclusive-response?
        }
        return conflicts
    }

    private fun isCausalityViolated(
        parent: AtomicThreadEvent?,
        dependencies: List<AtomicThreadEvent>,
        conflicts: List<AtomicThreadEvent>,
    ): Boolean {
        var causalityViolation = false
        // Check that parent does not depend on conflicting events.
        if (parent != null) {
            causalityViolation = causalityViolation || conflicts.any { conflict ->
                causalityOrder.orEqual(conflict, parent)
            }
        }
        // Also check that dependencies do not causally depend on conflicting events.
        causalityViolation = causalityViolation || conflicts.any { conflict -> dependencies.any { dependency ->
            causalityOrder.orEqual(conflict, dependency)
        }}
        return causalityViolation
    }

    private fun MutableExecutionFrontier<AtomicThreadEvent>.addUnblockingResponses(conflicts: List<AtomicThreadEvent>) {
        for (descriptor in blockedEvents.values) {
            val request = descriptor.request
            val response = descriptor.response
            if (request != this[request.threadId])
                continue
            if (request in conflicts || response in conflicts)
                continue
            if (response != null && response.dependencies.all { it in this }) {
                this.update(response)
            }
        }
    }

    private fun addEventToCurrentExecution(event: AtomicThreadEvent) {
        // Check if the added event is replayed event.
        val isReplayedEvent = inReplayPhase(event.threadId)
        // Update current execution and replayed frontier.
        if (!isReplayedEvent) {
            _execution.add(event)
        }
        playedFrontier.update(event)
        // Unblock the thread if the unblocking response was added.
        if (event.label.isResponse && event.label.isBlocking && isBlockedRequest(event.request!!)) {
            unblockRequest(event.request!!)
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
        // In this case, we need to proceed with all postponed non-replayed events.
        if (isReplayedEvent) {
            for (delayedEvent in delayedConsistencyCheckBuffer) {
                if (delayedEvent.label.isSend) {
                    addSynchronizedEvents(delayedEvent)
                }
            }
            delayedConsistencyCheckBuffer.clear()
            return
        }
        // If we are not in the replay phase and the newly added event is not replayed, then proceed as usual.
        // Add synchronized events.
        if (event.label.isSend) {
            addSynchronizedEvents(event)
        }
        // Check consistency of the new event.
        val inconsistency = execution.inconsistency
        if (inconsistency != null) {
            reportInconsistencyCallback(inconsistency)
        }
    }

    /* ************************************************************************* */
    /*      Replaying                                                            */
    /* ************************************************************************* */

    private class Replayer(private val executionOrder: List<ThreadEvent>) {
        private var index: Int = 0
        private var size: Int = 0

        constructor(): this(listOf())

        fun inProgress(): Boolean =
            (index < size)

        val currentEvent: AtomicThreadEvent?
            get() = if (inProgress()) (executionOrder[index] as? AtomicThreadEvent) else null

        fun setNextEvent() {
            index++
        }

        fun reset() {
            index = 0
            size = executionOrder.size
        }
    }

    fun inReplayPhase(): Boolean =
        replayer.inProgress()

    fun inReplayPhase(iThread: Int): Boolean {
        val frontEvent = playedFrontier[iThread]
            ?.ensure { it in _execution }
        return (frontEvent != execution.lastEvent(iThread))
    }

    // should only be called in replay phase!
    fun canReplayNextEvent(iThread: Int): Boolean {
        return iThread == replayer.currentEvent?.threadId
    }

    private fun tryReplayEvent(iThread: Int): AtomicThreadEvent? {
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
            ?.ensure { event -> event.dependencies.all { it in playedFrontier } }
            ?.also { replayer.setNextEvent() }
    }

    /* ************************************************************************* */
    /*      Object tracking                                                      */
    /* ************************************************************************* */

    fun allocationEvent(id: ObjectID): AtomicThreadEvent? {
        return objectRegistry[id]?.allocation
    }

    /* ************************************************************************* */
    /*      Synchronization                                                      */
    /* ************************************************************************* */

    private val EventLabel.syncType
        get() = syncAlgebra.syncType(this)

    private fun EventLabel.synchronize(other: EventLabel) =
        syncAlgebra.synchronize(this, other)

    private fun synchronizationCandidates(label: EventLabel): Sequence<AtomicThreadEvent> {
        // TODO: generalize the checks for arbitrary synchronization algebra?
        return when {
            // write can synchronize with read-request events
            label is WriteAccessLabel ->
                execution.memoryAccessEventIndex.getReadRequests(label.location).asSequence()

            // read-request can synchronize only with write events
            label is ReadAccessLabel && label.isRequest ->
                execution.memoryAccessEventIndex.getWrites(label.location).asSequence()

            // read-response cannot synchronize with anything
            label is ReadAccessLabel && label.isResponse ->
                sequenceOf()

            // re-entry lock-request synchronizes only with initializing unlock
            (label is LockLabel && label.isReentry) ->
                sequenceOf(allocationEvent(label.mutexID)!!)

            // re-entry unlock does not participate in synchronization
            (label is UnlockLabel && label.isReentry) ->
                sequenceOf()

            // random labels do not synchronize
            label is RandomLabel -> sequenceOf()

            // otherwise we pessimistically assume that any event can potentially synchronize
            else -> execution.asSequence()
        }
    }

    private fun synchronizationCandidates(event: AtomicThreadEvent): Sequence<AtomicThreadEvent> {
        val label = event.label
        // consider all the candidates and apply additional filters
        val candidates = synchronizationCandidates(label)
            // take only the events from the current execution
            .filter { it in execution }
            // for a send event we additionally filter out ...
            .runIf(event.label.isSend) {
                filter {
                    // (1) all of its causal predecessors, because an attempt to synchronize with
                    //     these predecessors will result in a causality cycle
                    !causalityOrder(it, event) &&
                    // (2) pinned events, because their response part is pinned,
                    //     unless the pinned event is blocked event
                    (!pinnedEvents.contains(it) || isBlockedRequest(it))
                }
            }
        return when {
            /* For read-request events, we search for the last write to
             * the same memory location in the same thread.
             * We then filter out all causal predecessors of this last write,
             * because these events are "obsolete" ---
             * reading from them will result in coherence cycle and will violate consistency
             */
            label is ReadAccessLabel && label.isRequest -> {
                if (execution.memoryAccessEventIndex.isRaceFree(label.location)) {
                    val lastWrite = execution.memoryAccessEventIndex.getLastWrite(label.location)!!
                    return sequenceOf(lastWrite)
                }
                val threadReads = execution[event.threadId]!!.filter {
                    it.label.isResponse && (it.label as? ReadAccessLabel)?.location == label.location
                }
                val lastSeenWrite = threadReads.lastOrNull()?.readsFrom
                val staleWrites = threadReads
                    .map { it.readsFrom }
                    .filter { it != lastSeenWrite }
                    .distinct()
                val eventFrontier = execution.calculateFrontier(event.causalityClock)
                val racyWrites = calculateRacyWrites(label.location, eventFrontier)
                candidates.filter {
                    // !causalityOrder.lessThan(it, threadLastWrite) &&
                    !racyWrites.any { write -> causalityOrder(it, write) } &&
                    !staleWrites.any { write -> causalityOrder.orEqual(it, write) }
                }
            }

            label is WriteAccessLabel -> {
                if (execution.memoryAccessEventIndex.isReadWriteRaceFree(label.location)) {
                    return sequenceOf()
                }
                candidates
            }

            // an allocation event, at the point when it is added to the execution,
            // cannot synchronize with anything, because there are no events yet
            // that access the allocated object
            label is ObjectAllocationLabel -> {
                return sequenceOf()
            }

            label is CoroutineSuspendLabel && label.isRequest -> {
                // filter-out InitializationLabel to prevent creating cancellation response
                // TODO: refactor!!!
                candidates.filter { it.label !is InitializationLabel }
            }

            else -> candidates
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
    private fun addSynchronizedEvents(event: AtomicThreadEvent): List<AtomicThreadEvent> {
        val candidates = synchronizationCandidates(event)
        val syncEvents = when (event.label.syncType) {
            SynchronizationType.Binary -> addBinarySynchronizedEvents(event, candidates)
            SynchronizationType.Barrier -> addBarrierSynchronizedEvents(event, candidates)
            else -> return listOf()
        }
        // if there are responses to blocked dangling requests, then set the response of one of these requests
        for (syncEvent in syncEvents) {
            val blockedRequest = syncEvent.parent?.takeIf { isBlockedRequest(it) }
                ?: continue
            if (!hasUnblockingResponse(blockedRequest)) {
                setUnblockingResponse(syncEvent)
                // mark corresponding backtracking point as visited;
                // search from the end, because the search event was added recently,
                // and thus should be located near the end of the list
                backtrackingPoints.last { it.event == syncEvent }.apply { visit() }
                break
            }
        }
        return syncEvents
    }

    private fun addBinarySynchronizedEvents(
        event: AtomicThreadEvent,
        candidates: Sequence<AtomicThreadEvent>
    ): List<AtomicThreadEvent> {
        require(event.label.syncType == SynchronizationType.Binary)
        // TODO: sort resulting events according to some strategy?
        return candidates
            .asIterable()
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
                createEvent(
                    iThread = parent.threadId,
                    label = syncLab,
                    parent = parent,
                    dependencies = listOf(dependency),
                    visit = false,
                )
            }
    }

    private fun addBarrierSynchronizedEvents(
        event: AtomicThreadEvent,
        candidates: Sequence<AtomicThreadEvent>
    ): List<AtomicThreadEvent> {
        require(event.label.syncType == SynchronizationType.Barrier)
        val (syncLab, dependencies) =
            candidates.fold(event.label to listOf(event)) { (lab, deps), candidateEvent ->
                candidateEvent.label.synchronize(lab)?.let {
                    (it to deps + candidateEvent)
                } ?: (lab to deps)
            }
        if (syncLab.isBlocking && !syncLab.isUnblocked)
            return listOf()
        // We assume that at most, one of the events participating into synchronization
        // is a request event, and the result of synchronization is a response event.
        check(syncLab.isResponse)
        val parent = dependencies.first { it.label.isRequest }
        val responseEvent = createEvent(
            iThread = parent.threadId,
            label = syncLab,
            parent = parent,
            dependencies = dependencies.filter { it != parent },
            visit = false,
        )
        return listOfNotNull(responseEvent)
    }

    /* ************************************************************************* */
    /*      Generic event addition utilities (per event kind)                    */
    /* ************************************************************************* */

    private fun addRootEvent(): AtomicThreadEvent {
        // we do not mark root event as visited purposefully;
        // this is just a trick to make the first call to `startNextExploration`
        // to pick the root event as the next event to explore from.
        val label = InitializationLabel(initThreadId, mainThreadId) { location ->
            val value = memoryInitializer(location)
            objectRegistry.getOrRegisterValueID(location.type, value)
        }
        return createEvent(initThreadId, label, parent = null, dependencies = emptyList(), visit = false)!!
            .also { event ->
                val id = STATIC_OBJECT_ID
                val entry = ObjectEntry(id, StaticObject.opaque(), event)
                objectRegistry.register(entry)
                addEventToCurrentExecution(event)
            }
    }

    private fun addEvent(iThread: Int, label: EventLabel, dependencies: List<AtomicThreadEvent>): AtomicThreadEvent {
        tryReplayEvent(iThread)?.let { event ->
            check(event.label == label)
            addEventToCurrentExecution(event)
            return event
        }
        val parent = playedFrontier[iThread]
        return createEvent(iThread, label, parent, dependencies)!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    private fun addSendEvent(iThread: Int, label: EventLabel): AtomicThreadEvent {
        require(label.isSend)
        return addEvent(iThread, label, listOf())
    }

    private fun addRequestEvent(iThread: Int, label: EventLabel): AtomicThreadEvent {
        require(label.isRequest)
        return addEvent(iThread, label, listOf())
    }

    private fun addResponseEvents(requestEvent: AtomicThreadEvent): Pair<AtomicThreadEvent?, List<AtomicThreadEvent>> {
        require(requestEvent.label.isRequest)
        tryReplayEvent(requestEvent.threadId)?.let { event ->
            check(event.label.isResponse)
            check(event.parent == requestEvent)
            check(event.label == event.resynchronize(syncAlgebra))
            addEventToCurrentExecution(event)
            return event to listOf(event)
        }
        if (isBlockedRequest(requestEvent)) {
            val event = getUnblockingResponse(requestEvent)
                ?: return (null to listOf())
            check(event.label.isResponse)
            check(event.request == requestEvent)
            addEventToCurrentExecution(event)
            return event to listOf(event)
        }
        val responseEvents = addSynchronizedEvents(requestEvent)
        if (responseEvents.isEmpty()) {
            blockRequest(requestEvent)
            return (null to listOf())
        }
        // TODO: use some other strategy to select the next event in the current exploration?
        // TODO: check consistency of chosen event!
        val chosenEvent = responseEvents.last().also { event ->
            check(event == backtrackingPoints.last().event)
            backtrackingPoints.last().visit()
            addEventToCurrentExecution(event)
        }
        return (chosenEvent to responseEvents)
    }

    /* ************************************************************************* */
    /*      Blocking events handling                                             */
    /* ************************************************************************* */

    /**
     * Descriptor of a blocked event.
     *
     * @property request The request part of blocked event.
     * @property response The response part of the blocked event.
     *
     * @see [blockedEvents]
     */
    class BlockedEventDescriptor(val request: AtomicThreadEvent) {

        init {
            require(request.label.isRequest)
            require(request.label.isBlocking)
        }

        var response: AtomicThreadEvent? = null
            private set

        fun setResponse(response: AtomicThreadEvent) {
            require(response.label.isResponse)
            require(response.label.isBlocking)
            require(this.request == response.request)
            check(this.response == null)
            this.response = response
        }
    }

    private fun isBlockedRequest(request: AtomicThreadEvent): Boolean {
        return (request == blockedEvents[request.threadId]?.request)
    }

    private fun blockRequest(request: AtomicThreadEvent) {
        require(execution.isBlockedDanglingRequest(request))
        blockedEvents.put(request.threadId, BlockedEventDescriptor(request)).ensureNull()
    }

    private fun unblockRequest(request: AtomicThreadEvent) {
        require(request.label.isRequest && request.label.isBlocking)
        require(!execution.isBlockedDanglingRequest(request))
        check(request == blockedEvents[request.threadId]!!.request)
        blockedEvents.remove(request.threadId)
    }

    private fun hasUnblockingResponse(request: AtomicThreadEvent): Boolean {
        return (getUnblockingResponse(request) != null)
    }

    private fun getUnblockingResponse(request: AtomicThreadEvent): AtomicThreadEvent? {
        require(execution.isBlockedDanglingRequest(request))
        val descriptor = blockedEvents[request.threadId].ensure {
            it != null && it.request == request
        }
        return descriptor!!.response
    }

    private fun setUnblockingResponse(response: AtomicThreadEvent) {
        require(response.label.isResponse && response.label.isBlocking)
        require(execution.isBlockedDanglingRequest(response.request!!))
        val descriptor = blockedEvents[response.threadId]!!
        descriptor.setResponse(response)
    }

    fun getPendingBlockingRequest(iThread: Int): AtomicThreadEvent? =
        playedFrontier[iThread]?.takeIf { it.label.isRequest && it.label.isBlocking }

    fun isPendingUnblockedRequest(request: AtomicThreadEvent): Boolean {
        require(playedFrontier.isBlockedDanglingRequest(request))
        // if we are in replay phase, then the request is unblocked
        // if we can replay its response part
        if (inReplayPhase(request.threadId)) {
            return canReplayNextEvent(request.threadId)
        }
        // otherwise, the request is unblocked if its response part was already created
        val descriptor = blockedEvents[request.threadId]
        if (descriptor != null) {
            check(request == descriptor.request)
            return (descriptor.response != null)
        }
        return true
    }

    /* ************************************************************************* */
    /*      Specific event addition utilities (per event class)                  */
    /* ************************************************************************* */

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
            check(event.label is ObjectAllocationLabel)
            val id = event.label.objectID
            val entry = ObjectEntry(id, value, event)
            objectRegistry.register(entry)
            addEventToCurrentExecution(event)
            return event
        }
        val id = objectRegistry.nextObjectID
        val label = ObjectAllocationLabel(
            objectID = id,
            className = value.unwrap().javaClass.simpleName,
            memoryInitializer = { location ->
                val initValue = memoryInitializer(location)
                objectRegistry.getOrRegisterValueID(location.type, initValue)
            },
        )
        val parent = playedFrontier[iThread]
        val dependencies = listOf<AtomicThreadEvent>()
        return createEvent(iThread, label, parent, dependencies)!!.also { event ->
            val entry = ObjectEntry(id, value, event)
            objectRegistry.register(entry)
            addEventToCurrentExecution(event)
        }
    }

    fun addWriteEvent(iThread: Int, codeLocation: Int, location: MemoryLocation, value: ValueID,
                      readModifyWriteDescriptor: ReadModifyWriteDescriptor? = null): AtomicThreadEvent {
        val label = WriteAccessLabel(
            location = location,
            writeValue = value, // TODO: change API of other methods to also take ValueID
            readModifyWriteDescriptor = readModifyWriteDescriptor,
            codeLocation = codeLocation,
        )
        return addSendEvent(iThread, label)
    }

    fun addReadRequest(iThread: Int, codeLocation: Int, location: MemoryLocation,
                       readModifyWriteDescriptor: ReadModifyWriteDescriptor? = null): AtomicThreadEvent {
        // we create a read-request event with an unknown (null) value,
        // value will be filled later in the read-response event
        val label = ReadAccessLabel(
            kind = LabelKind.Request,
            location = location,
            readValue = NULL_OBJECT_ID,
            readModifyWriteDescriptor = readModifyWriteDescriptor,
            codeLocation = codeLocation,
        )
        return addRequestEvent(iThread, label)
    }

    fun addReadResponse(iThread: Int): AtomicThreadEvent {
        val readRequest = playedFrontier[iThread].ensure {
            it != null && it.label.isRequest && it.label is ReadAccessLabel
        }
        val (responseEvent, _) = addResponseEvents(readRequest!!)
        // TODO: think again --- is it possible that there is no write to read-from?
        //  Probably not, because in Kotlin variables are always initialized by default?
        //  What about initialization-related issues?
        checkNotNull(responseEvent)
        if (isSpinLoopBoundReached(responseEvent)) {
            internalThreadSwitchCallback(responseEvent.threadId, SwitchReason.SPIN_BOUND)
        }
        return responseEvent
    }

    fun addLockRequestEvent(iThread: Int, mutex: OpaqueValue,
                            isReentry: Boolean = false, reentrancyDepth: Int = 1,
                            isSynthetic: Boolean = false): AtomicThreadEvent {
        val label = LockLabel(
            kind = LabelKind.Request,
            mutexID = objectRegistry.getOrRegisterObjectID(mutex),
            isReentry = isReentry,
            reentrancyDepth = reentrancyDepth,
            isSynthetic = isSynthetic,
        )
        return addRequestEvent(iThread, label)
    }

    fun addLockResponseEvent(lockRequest: AtomicThreadEvent): AtomicThreadEvent? {
        require(lockRequest.label.isRequest && lockRequest.label is LockLabel)
        return addResponseEvents(lockRequest).first
    }

    fun addUnlockEvent(iThread: Int, mutex: OpaqueValue,
                       isReentry: Boolean = false, reentrancyDepth: Int = 1,
                       isSynthetic: Boolean = false): AtomicThreadEvent {
        val label = UnlockLabel(
            mutexID = objectRegistry.getOrRegisterObjectID(mutex),
            isReentry = isReentry,
            reentrancyDepth = reentrancyDepth,
            isSynthetic = isSynthetic,
        )
        return addSendEvent(iThread, label)
    }

    fun addWaitRequestEvent(iThread: Int, mutex: OpaqueValue): AtomicThreadEvent {
        val label = WaitLabel(
            kind = LabelKind.Request,
            mutexID = objectRegistry.getOrRegisterObjectID(mutex),
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
            mutexID = objectRegistry.getOrRegisterObjectID(mutex),
            isBroadcast = isBroadcast,
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
        val request = getPendingBlockingRequest(iThread)!!.ensure { event ->
            event.label.satisfies<CoroutineSuspendLabel> { actorId == iActor }
        }
        val (response, events) = addResponseEvents(request)
        check(events.size == 1)
        return response!!
    }

    fun addCoroutineCancelResponseEvent(iThread: Int, iActor: Int): AtomicThreadEvent {
        val request = getPendingBlockingRequest(iThread)!!.ensure { event ->
            event.label.satisfies<CoroutineSuspendLabel> { actorId == iActor }
        }
        val label = (request.label as CoroutineSuspendLabel).getResponse(root.label)!!
        tryReplayEvent(iThread)?.let { event ->
            check(event.label == label)
            addEventToCurrentExecution(event)
            return event
        }
        return createEvent(iThread, label, parent = request, dependencies = listOf(root))!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    fun addCoroutineResumeEvent(iThread: Int, iResumedThread: Int, iResumedActor: Int): AtomicThreadEvent? {
        val label = CoroutineResumeLabel(iResumedThread, iResumedActor)
        for (event in execution) {
            if (event in playedFrontier && event.label == label) return null
        }
        tryReplayEvent(iThread)?.let { event ->
            check(event.label == label)
            addEventToCurrentExecution(event)
            return event
        }
        val parent = playedFrontier[iThread]
        return createEvent(iThread, label, parent, dependencies = listOf())!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    fun addActorStartEvent(iThread: Int, actor: Actor): AtomicThreadEvent {
        val label = ActorLabel(SpanLabelKind.Start, iThread, actor)
        return addEvent(iThread, label, dependencies = listOf()).also {
            resetReadCodeLocationsCounter(iThread)
        }
    }

    fun addActorEndEvent(iThread: Int, actor: Actor): AtomicThreadEvent {
        val label = ActorLabel(SpanLabelKind.End, iThread, actor)
        return addEvent(iThread, label, dependencies = listOf())
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
        return createEvent(iThread, label, parent, dependencies = emptyList())!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    /* ************************************************************************* */
    /*      Miscellaneous                                                        */
    /* ************************************************************************* */

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
                ?.ensure { it in execution }
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
        observation: ExecutionFrontier<AtomicThreadEvent>
    ): List<ThreadEvent> {
        val writes = calculateMemoryLocationView(location, observation).events
        return writes.filter { write ->
            !writes.any { other ->
                causalityOrder(write, other)
            }
        }
    }

    private fun isSpinLoopBoundReached(event: ThreadEvent): Boolean {
        check(event.label is ReadAccessLabel && event.label.isResponse)
        val readLabel = (event.label as ReadAccessLabel)
        val location = readLabel.location
        val readValue = readLabel.readValue
        val codeLocation = readLabel.codeLocation
        // check code locations counter to detect spin-loop
        val counter = readCodeLocationsCounter.compute(event.threadId to codeLocation) { _, count ->
            1 + (count ?: 0)
        }!!
        // a potential spin-loop occurs when we have visited the same code location more than N times
        if (counter < SPIN_BOUND)
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

    private fun resetReadCodeLocationsCounter(iThread: Int) {
        // reset all code-locations counters of the given thread
        readCodeLocationsCounter.keys.retainAll { (tid, _) -> tid != iThread }
    }

}