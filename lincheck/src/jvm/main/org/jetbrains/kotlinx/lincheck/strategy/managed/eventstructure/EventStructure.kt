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
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.MemoryOrdering
import org.jetbrains.lincheck.util.*
import org.jetbrains.lincheck.util.collections.*


internal class EventStructure(
    private val memoryModel: MemoryModel,
    val memoryInitializer: MemoryInitializer,
    // TODO: refactor --- avoid using callbacks!
    private val reportInconsistencyCallback: ReportInconsistencyCallback,
    private val currentThreadIdCallback: CurrentThreadIdCallback,
    private val onThreadReplayFinished: (ThreadId) -> Unit,
    private val internalThreadSwitchCallback: InternalThreadSwitchCallback,
) {
    val mainThreadId = 0
    val initThreadId = -1 // TODO: This ID may be very wrong?

    /**
     * Mutable list of the event structure events.
     */
    private val _events = sortedMutableListOf<AtomicThreadEvent>()

    /**
     * List of the event structure events.
     */
    val events: SortedList<AtomicThreadEvent> = _events
    val eventStructureObjectTracker = EventStructureObjectTracker(this, currentThreadIdCallback)

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
    private var _execution = MutableExtendedExecution(memoryModel)

    /**
     * The execution currently being explored.
     */
    val execution: ExtendedExecution
        get() = _execution

    /**
     * The frontier representing an already replayed part of the execution currently being explored.
     */
    private var playedFrontier = MutableExecutionFrontier<AtomicThreadEvent>()

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
     * Backtracking must retain these events.
     */
    private var pinnedEvents = ExecutionFrontier<AtomicThreadEvent>()

    /**
     * For each blocked thread, stores a descriptor of the blocked event.
     *
     * The thread may become blocked when it issues a request-event
     * denoting some blocking operation (e.g., mutex lock),
     * such that the corresponding response-event cannot be created immediately
     * (for example, because no matching notification has been issued).
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
        mutableThreadMapOf()

    private val delayedConsistencyCheckBuffer = mutableListOf<AtomicThreadEvent>()

    private val readCodeLocationsCounter = mutableMapOf<Pair<Int, Int>, Int>()

    init {
        //TODO: I do not like that we have to do this, but addRootEvent relies on
        // having the initThreadId and mainThreadId registered
        registerThread(initThreadId)
        registerThread(mainThreadId)
        root = addRootEvent()
        eventStructureObjectTracker.initialize(root)
    }


    fun registerThread(threadId: ThreadId) {
        _execution.registerThread(threadId)
    }

    /* ************************************************************************* */
    /*      Exploration                                                          */
    /* ************************************************************************* */

    fun startNextExploration(): Boolean {
        while (true) {
            val backtrackingPoint = rollbackTo { !it.visited }
                ?: return false
            backtrackingPoint.visit()
            if (resetExploration(backtrackingPoint)) return true
        }
    }

    fun initializeExploration() {
        // reset re-played frontier
        playedFrontier = MutableExecutionFrontier()
        playedFrontier[initThreadId] = execution[initThreadId]!!.last()
        // reset replayer state
        replayer.reset()
        if (replayer.inProgress()) {
            replayer.currentEvent.ensure {
                it != null && it.label is InitializationLabel
            }
            replayer.setNextEvent()
        }
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

    /**
     * Resets the exploration to the given [backtrackingPoint].
     *
     * @return true if the exploration was successfully reset,
     *   false if the exploration rooted in [backtrackingPoint] should be skipped.
     */
    private fun resetExploration(backtrackingPoint: BacktrackingPoint): Boolean {
        // get the event to backtrack to
        val event = backtrackingPoint.event.ensure {
            it.label is InitializationLabel || it.label.isResponse
        }
        val maximalityChecker = backtrackingPoint.maximalityChecker
        if (maximalityChecker != null) {
            // First reset to the frontier retained by maximality check:
            // it does not include the send and response events of the revisit,
            // as adding those events to execution may change the order relations used by maximality check
            _execution.reset(maximalityChecker.retainedFrontier)
            // If the retained frontier is inconsistent or not maximal, skip the revisit;
            // maximality check ensures each "backward" revisit will only be explored once.
            if (_execution.checkConsistency() != null || !maximalityChecker.isMaximal(execution)) return false
        }

        val frontier = backtrackingPoint.frontier.copy().apply {
            update(event)
            for (blockedRequest in backtrackingPoint.blockedRequests) update(blockedRequest)
        }
        // If the retained frontier is a prefix of the full frontier,
        // then simply add missing events, otherwise reset the execution completely.
        // Cutting dangling requests might have made the two prefixes differ by more than an extension.
        if (maximalityChecker != null && _execution.toFrontier().isPrefixOf(frontier)) {
            _execution.extend(frontier)
        } else {
            _execution.reset(frontier)
        }

        // reset blocked events
        blockedEvents.clear()
        // set current exploration root
        currentExplorationRoot = event
        for (blockedRequest in backtrackingPoint.blockedRequests) {
            blockRequest(blockedRequest)
        }
        // set pinned events
        this.pinnedEvents = backtrackingPoint.pinnedEvents.ensure {
            execution.containsAll(it.events)
        }
        // Reuse incrementally extended witnesses, or recompute if their extension failed.
        _execution.checkConsistency()

        // set the replayer state
        // We replay the events in the order that we added them
        val replayOrdering = _execution.sortedBy { it.id }
        replayer = Replayer(replayOrdering)
        return true
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
     * @property pinnedEvents The frontier of pinned events that must survive subsequent revisits.
     * @property blockedRequests The list of blocked request events.
     * @property maximalityChecker The pending maximality check for a backward revisit, if required.
     * @property visited Flag to indicate if this backtracking point has been visited.
     */
    private class BacktrackingPoint(
        val event: AtomicThreadEvent,
        val frontier: ExecutionFrontier<AtomicThreadEvent>,
        val pinnedEvents: ExecutionFrontier<AtomicThreadEvent>,
        val blockedRequests: List<AtomicThreadEvent>,
        val maximalityChecker: MaximalRevisitChecker?,
    ) : Comparable<BacktrackingPoint> {

        var visited: Boolean = false
            private set

        fun visit() {
            visited = true
        }

        override fun compareTo(other: BacktrackingPoint): Int = event.id.compareTo(other.event.id)

    }

    /**
     * Creates a backtracking point to explore the given not yet visited [event] later.
     *
     * The point is not created if exploring it would remove an event pinned in the current exploration,
     * i.e., a pinned event that is neither in the point's frontier nor among its blocked requests.
     * In this case, the caller should discard [event]:
     * every event created without being visited immediately must have a backtracking point,
     * since it is later looked up to choose or unblock the event in the current exploration.
     *
     * @param event The event to start a new exploration from.
     * @param conflicts The events conflicting with [event], to be removed in the new exploration.
     * @param maximalityChecker The pending maximality check for a backward revisit, if required.
     * @return true if the backtracking point was created,
     *   false if it was skipped because it would remove a pinned event.
     */
    private fun createBacktrackingPoint(
        event: AtomicThreadEvent,
        conflicts: List<AtomicThreadEvent>,
        maximalityChecker: MaximalRevisitChecker?,
    ): Boolean {
        // This check is guaranteed to hold for now, since this is called only by the
        // [addBinarySynchronizedEvents] and [addBarrierSynchronizedEvents] methods
        // which always pass in a parent.
        check((event.label !is InitializationLabel) implies (event.parent != null)) {
            "Backtracked event must have a parent: $event"
        }

        val request = event.parent
        val pendingCompletion = isPendingLockCompletion(request, conflicts)
        val frontier = execution.toMutableFrontier().apply {
            cut(conflicts)
            addUnblockingResponses(conflicts)
            if (request != null && event.senders.any { it.id > request.id } && !pendingCompletion) {
                cutForBackwardRevisit(request, event)
            }
        }

        // If the new event takes a lock away from another thread, cutting the conflicts removes
        // that thread's lock acquisition, and possibly also its lock request.
        // Keep the lock request (and any currently blocked lock request), so the thread waits
        // for the lock in the new exploration and can acquire it after a later unlock.
        // Re-add such a request only if its thread prefix and dependencies are all still in the frontier:
        // restoring missing earlier events could bring back the cut lock acquisition.
        val pendingLocks =
            conflicts.filter { it.label.isNonReentrantLockResponse }.mapNotNull { it.parent } +
            blockedEvents.values.map { it.request }.filter { it.label is LockLabel }
        for (pending in pendingLocks.distinct()) {
            if (pending == request || pending in frontier) continue
            if (frontier[pending.threadId] == pending.parent && pending.dependencies.all { it in frontier }) {
                frontier.update(pending)
            }
        }

        val danglingRequests = frontier.getDanglingRequests()
        val blockedRequests = danglingRequests
            .filter {
                it != request && (it.label.satisfies<LockLabel> { !isReentry } ||
                blockedEvents.values.any { blockedDesc -> blockedDesc.request == it })
            }

        frontier.apply {
            cut(danglingRequests)
            set(event.threadId, event.parent)
        }
        if (pinnedEvents.events.any { it !in frontier && it !in blockedRequests }) return false

        val backtrackingPoint = BacktrackingPoint(
            event = event,
            frontier = frontier,
            pinnedEvents = computePinnedEvents(event, conflicts, frontier, blockedRequests),
            blockedRequests = blockedRequests,
            maximalityChecker = maximalityChecker,
        )
        backtrackingPoints.add(backtrackingPoint)
        return true
    }

    private fun computePinnedEvents(
        event: AtomicThreadEvent,
        conflicts: List<AtomicThreadEvent>,
        frontier: ExecutionFrontier<AtomicThreadEvent>,
        blockedRequests: List<AtomicThreadEvent>,
    ): ExecutionFrontier<AtomicThreadEvent> {
        val newPinnedEvents = pinnedEvents.copy()
        val pinRoot = getPinnedCausalityRoot(event, conflicts) ?: return newPinnedEvents
        // Use the retained branch, since the old response can occupy the new response's position.
        val causalityFrontier = frontier.copy().apply {
            cut { !causalityOrder.orEqual(it, pinRoot) }
            for (request in blockedRequests) {
                if (causalityOrder.orEqual(request, pinRoot)) set(request.threadId, request)
            }
            if (pinRoot == event) set(event.threadId, event)
        }
        newPinnedEvents.merge(causalityFrontier)
        return newPinnedEvents
    }

    private fun getPinnedCausalityRoot(event: AtomicThreadEvent, conflicts: List<AtomicThreadEvent>): AtomicThreadEvent? {
        if (!event.label.isMaximalityCheckedResponse) return null
        val request = event.parent!!
        val sender = event.senders.single()
        if (sender.id > request.id) return sender
        // Eviction acts as an implicit acquisition write that makes the previous owner block.
        return event.takeIf {
            event.label.isNonReentrantLockResponse &&
            conflicts.any { it.label.isNonReentrantLockResponse && it.parent != request }
        }
    }

    /**
     * Creates a new event and adds it to the event structure, unless the event must be skipped.
     *
     * The event is not added to the current execution: the caller does this
     * if the event is visited immediately or appended in place.
     *
     * @param iThread The thread of the new event.
     * @param label The label of the new event.
     * @param parent The program-order predecessor of the new event, or null for the initialization event.
     * @param dependencies The events the new event synchronizes with, e.g., the write a read-response reads from.
     * @param visit If true, the caller visits the new event in the current exploration;
     *   otherwise, a backtracking point is created to explore it later (see [createBacktrackingPoint]).
     * @param appendInPlace If true, the new event must be appendable to the current execution as-is:
     *   it must have no conflicts, depend only on the already played events, and keep the execution consistent.
     * @return The new event, or null if it was skipped:
     *   - because exploring it would remove a pinned event or its own causes,
     *   - because a backward revisit to it is redundant (see [prepareMaximalRevisitCheck]),
     *   - or because it cannot be appended in place when [appendInPlace] is set.
     */
    private fun createEvent(
        iThread: Int,
        label: EventLabel,
        parent: AtomicThreadEvent?,
        dependencies: List<AtomicThreadEvent>,
        visit: Boolean = true,
        appendInPlace: Boolean = false,
    ): AtomicThreadEvent? {
        val conflicts = getConflictingEvents(iThread, label, parent, dependencies)

        // Exploring the event would remove its conflicts.
        // Skip it if any of them is pinned, since pinned events must survive all subsequent revisits.
        // An event appended in place is added to the current execution as-is, so it cannot have conflicts at all.
        if (conflicts.any { it in pinnedEvents } || (appendInPlace && conflicts.isNotEmpty())) return null
        // Skip the event if its parent or dependencies causally depend on its conflicts:
        // removing the conflicts would remove the event's own causes.
        if (isCausalityViolated(parent, dependencies, conflicts)) return null

        var maximalityChecker: MaximalRevisitChecker? = null
        if (label.isMaximalityCheckedResponse) {
            val request = parent!!
            val sender = dependencies.single()
            if (sender.id > request.id && !isPendingLockCompletion(request, conflicts)) {
                maximalityChecker = prepareMaximalRevisitCheck(execution, request, sender, conflicts) ?: return null
            }
        }

        val allocation = eventStructureObjectTracker.getAllocation(label.objectID)
        val source = (label as? WriteAccessLabel)?.writeValue?.let {
            eventStructureObjectTracker.getAllocation(it.toInt())
        }
        val event = AtomicThreadEventImpl(
            label = label,
            parent = parent,
            senders = dependencies,
            allocation = allocation,
            source = source,
            dependencies = listOfNotNull(allocation, source) + dependencies,
        )

        // An event appended in place must extend the prefix executed so far in the current exploration:
        // all its dependencies must already be played,
        // and appending it must keep the execution consistent (see [canAppendLockResponse]).
        if (appendInPlace && (
                event.dependencies.any { it !in playedFrontier } ||
                !canAppendLockResponse(event)
        )) return null

        // If the event is not visited immediately,
        // then we create a backtracking point to visit it later
        if (!visit) {
            if (!createBacktrackingPoint(event, conflicts, maximalityChecker)) return null
        }
        _events.add(event)
        return event
    }

    private fun getConflictingEvents(
        iThread: Int,
        label: EventLabel,
        parent: AtomicThreadEvent?,
        dependencies: List<ThreadEvent>
    ): List<AtomicThreadEvent> {
        val position = parent?.let { it.threadPosition + 1 } ?: 0
        val conflicts = mutableListOf<AtomicThreadEvent>()
        // if the current execution already has an event in given position --- then it is conflict
        execution[iThread, position]?.also { conflicts.add(it) }
        // handle label specific cases
        // TODO: unify this logic for various kinds of labels?
        when {
            label is LockLabel && label.isResponse && !label.isReentry -> {
                val unlock = dependencies.single()
                val grantedResponses = blockedEvents.values.mapNotNull { it.response }
                for (event in execution + grantedResponses) {
                    if (event.label.isNonReentrantLockResponse &&
                        (event.label as LockLabel).mutexID == label.mutexID &&
                        event.locksFrom == unlock
                    ) {
                        conflicts.add(event)
                    }
                }
            }

            label is ReadAccessLabel && label.isResponse && label.isExclusive -> run {
                // NOTE: we assume that the first write dependency is the write that this read reads from.
                // So this should work just like the [AtomicThreadEvent.readsFrom] method.
                // As we do not have an event, but just a label and a list of dependencies, we do it manually.
                val write = dependencies.find { it.label.isWriteAccess() }!! as AtomicThreadEvent

                // If the read reads from a write that existed before its request (a "forward" choice),
                // do not treat the other RMWs reading from the same write as conflicts: keep them in the execution.
                // If such an RMW exists, adding the write part of this RMW will make the execution inconsistent
                // (two RMWs read from the same write), but only after the backtracking points for this write
                // were created, including the ones that let this RMW go before the other RMWs.
                if (write.id < parent!!.id) return@run

                val writeLabel = write.label
                val location = label.location
                check(writeLabel.isWriteAccess())
                // We check if there are any other successful RMW events that read from the same write event
                // as the write event this read reads from.
                // In that case they are the conflicting events.
                for (readEvent in execution.memoryAccessEventIndex.getWriteReadResponses(write, location)) {
                    check(readEvent.readsFrom == write)
                    val otherLabel = readEvent.label
                    // Make sure the read is exclusive
                    otherLabel.refine<ReadAccessLabel> { isExclusive } ?: continue
                    // Make sure that it is successful by checking that the next event for the thread is a rmw write
                    execution.getExclusiveWriteForReadResponse(readEvent) ?: continue
                    conflicts.add(readEvent)
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

        return conflicts.distinct()
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
        // Fresh exploration gets its own loop budget, independently for each replaying thread.
        if (isReplayedEvent && !inReplayPhase(event.threadId)) {
            onThreadReplayFinished(event.threadId)
        }
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
            resumeRestoredLockRequests()
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

        constructor() : this(listOf())

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
                //TODO: This should not be null!
                internalThreadSwitchCallback(iThread, null)
            } while (inReplayPhase() && !canReplayNextEvent(iThread))
        }
        return replayer.currentEvent
            ?.ensure { event -> event.dependencies.all { it in playedFrontier } }
            ?.also { replayer.setNextEvent() }
    }

    /* ************************************************************************* */
    /*      Object tracking                                                      */
    /* ************************************************************************* */

    fun allocationEvent(id: ObjectNumber): AtomicThreadEvent? {
        return eventStructureObjectTracker.getAllocation(id)
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

            // non-reentry lock-request can synchronize with the mutex allocation (initial unlock) or unlock events,
            // except those already taken by a pinned lock-response
            // (synchronizing with them would conflict with the pinned lock-response, which cannot be removed)
            (label is LockLabel && !label.isReentry) -> {
                val pinnedUnlocks = pinnedEvents.toExecution().asSequence()
                    .filter { it.label.isNonReentrantLockResponse && (it.label as LockLabel).mutexID == label.mutexID }
                    .map { it.locksFrom }
                    .toSet()
                val allocationEvent = allocationEvent(label.mutexID)!!
                val unlockEvents = execution.filter {
                    it.label.isNonReentrantUnlock && (it.label as UnlockLabel).mutexID == label.mutexID
                }
                (sequenceOf(allocationEvent) + unlockEvents).filter { it !in pinnedUnlocks }
            }

            // re-entry lock-request synchronizes only with initializing unlock
            (label is LockLabel && label.isReentry) ->
                sequenceOf(allocationEvent(label.mutexID)!!)

            // re-entry unlock does not participate in synchronization
            (label is UnlockLabel && label.isReentry) ->
                sequenceOf()

            // Random choices and constructor freezes do not synchronize.
            label is RandomLabel || label is FreezeLabel -> sequenceOf()

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
            // for a send event we additionally filter out all of its causal predecessors,
            // because an attempt to synchronize with these predecessors
            // will result in a causality cycle
            .runIf(event.label.isSend) {
                filter { !causalityOrder(it, event) }
            }
        return when {
            // an allocation event, at the point when it is added to the execution,
            // cannot synchronize with anything, because there are no events yet
            // that access the allocated object
            label is ObjectAllocationLabel -> {
                sequenceOf()
            }

            label is CoroutineSuspendLabel && label.isRequest -> {
                // filter-out InitializationLabel to prevent creating cancellation response
                // TODO: refactor!!!
                candidates.filter { it.label !is InitializationLabel }
            }

            else -> _execution.filterInconsistent(event, candidates)
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
    private fun addSynchronizedEvents(
        event: AtomicThreadEvent,
        candidates: Sequence<AtomicThreadEvent> = synchronizationCandidates(event),
    ): List<AtomicThreadEvent> {
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
                if (syncEvent.label.isNonReentrantLockResponse && !canAppendLockResponse(syncEvent)) continue
                takeUnblockingResponse(syncEvent)
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
        return candidates
            .distinct()
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
                SynchronizationCandidate(syncLab, parent, dependency)
            }
            .sortedByCreationOrder(event)
            .mapNotNull { candidate ->
                createEvent(
                    iThread = candidate.request.threadId,
                    label = candidate.responseLabel,
                    parent = candidate.request,
                    dependencies = listOf(candidate.send),
                    visit = false,
                )
            }
    }

    /**
     * A possible binary synchronization of a [request] with a [send], producing a response with [responseLabel].
     */
    private data class SynchronizationCandidate(
        val responseLabel: EventLabel,
        val request: AtomicThreadEvent,
        val send: AtomicThreadEvent,
    )

    /**
     * Sorts the synchronization candidates of [event] in the order in which their responses should be created.
     *
     * For a send event, the candidates are sorted by their requests' order.
     * For a request event, they are sorted by their sends' order (e.g., coherence order for writes),
     * so that the last created response is the one chosen in the current exploration.
     */
    private fun List<SynchronizationCandidate>.sortedByCreationOrder(
        event: AtomicThreadEvent,
    ): List<SynchronizationCandidate> {
        val label = event.label
        return when {
            label is ReadAccessLabel ->
                execution.coherenceOrder.sort(this, label.location) { it.send }
            label is LockLabel && !label.isReentry ->
                execution.lockOrder.sort(this) { it.send }
            label.isSend ->
                sortedBy { it.request.id }
            else ->
                sortedBy { it.send.id }
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
            eventStructureObjectTracker.getOrRegisterValueID(location.type, value)
        }
        return createEvent(initThreadId, label, parent = null, dependencies = emptyList(), visit = false)!!
            .also { event ->
                // TODO: Figure out how to load the static object
                // val id = STATIC_OBJECT_NUMBER
                // val entry = RegistryObjectEntry(id, StaticObject.opaque(), event)
                // objectRegistry.register(entry)
                addEventToCurrentExecution(event)
            }
    }

    private fun addEvent(iThread: Int, label: EventLabel, dependencies: List<AtomicThreadEvent>): AtomicThreadEvent {
        tryReplayEvent(iThread)?.let { event ->
            check(event.label == label) { "Expected to replay event with label ${event} but got ${label}" }
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
        // A restored marker has no replay response and must never regenerate forward choices.
        if (isBlockedRequest(requestEvent)) {
            val event = getUnblockingResponse(requestEvent)
                ?: return (null to listOf())
            if (event.dependencies.any { it !in playedFrontier }) return (null to listOf())
            addEventToCurrentExecution(event)
            return event to listOf(event)
        }
        tryReplayEvent(requestEvent.threadId)?.let { event ->
            val resyncLabel = event.resynchronize(syncAlgebra)
            check(event.label.isResponse)
            check(event.parent == requestEvent)
            check(event.label == resyncLabel)
            addEventToCurrentExecution(event)
            return event to listOf(event)
        }
        val responseEvents = addSynchronizedEvents(requestEvent)
        val chosenEvent = responseEvents.chooseResponse(requestEvent)
        if (chosenEvent == null) {
            blockRequest(requestEvent)
            return (null to responseEvents)
        }
        backtrackingPoints.last { it.event == chosenEvent }.visit()
        addEventToCurrentExecution(chosenEvent)
        return (chosenEvent to responseEvents)
    }

    /**
     * Chooses the response to [requestEvent] to visit in the current exploration.
     * The responses are expected in their creation order (see [sortedByCreationOrder]),
     *
     * @return The chosen response, or null if there is none and the request should block.
     */
    private fun List<AtomicThreadEvent>.chooseResponse(requestEvent: AtomicThreadEvent): AtomicThreadEvent? =
        if (requestEvent.label.satisfies<LockLabel> { !isReentry }) {
            // A non-reentrant lock response is chosen only if it can be appended to the current execution.
            lastOrNull { canAppendLockResponse(it) }
        } else {
            lastOrNull()
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
            return descriptor.response?.dependencies?.all { it in playedFrontier } == true
        }
        return true
    }

    /**
     * Checks if the [request] event is a pending lock that can be completed in-place in current exploration.
     * Completing this [request] appends to the current execution without replacing an existing response.
     */
    private fun isPendingLockCompletion(request: AtomicThreadEvent?, conflicts: List<AtomicThreadEvent>): Boolean =
        request != null &&
        request.label.isNonReentrantLockRequest &&
        isBlockedRequest(request) &&
        conflicts.isEmpty()

    /**
     * Grants the [response] to its blocked request in the current exploration,
     * and marks the response's backtracking point as visited.
     */
    private fun takeUnblockingResponse(response: AtomicThreadEvent) {
        val point = backtrackingPoints.last { it.event == response }
        setUnblockingResponse(response)
        // An inline grant keeps the current prefix; pinning it would exclude later contenders.
        point.visit()
    }

    /**
     * Creates lock responses for the blocked lock requests from the unlocks of the replayed prefix
     * that are not taken by any lock response.
     * Called when the replay phase finishes, since replayed unlocks do not create synchronizations themselves.
     */
    private fun resumeRestoredLockRequests() {
        // Replayed releases do not generate synchronizations again.
        // A surviving marker may nevertheless be enabled by one of them after its old consumer was cut.
        val sources = execution.filter { it.label.isNonReentrantUnlock && it.id < currentExplorationRoot.id }
        for (source in sources.sortedBy { it.id }) {
            val consumers = execution.asSequence() + blockedEvents.values.mapNotNull { it.response }.asSequence()
            if (consumers.any { it.label.isNonReentrantLockResponse && it.locksFrom == source }) continue
            val requests = blockedEvents.values.asSequence()
                .filter { it.response == null }
                .map { it.request }
                .filter { it.label.satisfies<LockLabel> { !isReentry } && it.id < source.id }
            // Generate every waiter's alternative before reserving the free source for one of them.
            addSynchronizedEvents(source, requests)
        }
    }

    /**
     * Checks if the lock [response] can be appended to the current execution,
     * taking into account the responses already granted to the blocked requests.
     * This requires its request to be the last event of its thread, its dependencies to be present,
     * and the extended execution to remain consistent.
     */
    private fun canAppendLockResponse(response: AtomicThreadEvent): Boolean {
        val frontier = execution.toMutableFrontier()
        // A queued grant reserves its source even before the waiting thread is scheduled.
        for (descriptor in blockedEvents.values) {
            val granted = descriptor.response ?: continue
            if (frontier[granted.threadId] == granted.parent) frontier.update(granted)
        }
        if (frontier[response.threadId] != response.parent || response.dependencies.any { it !in frontier }) return false
        frontier.update(response)
        return MutableExtendedExecution(memoryModel).apply { reset(frontier) }.checkConsistency() == null
    }

    /**
     * Grants a response to one of the blocked lock requests in the current exploration,
     * using an unlock that is not taken by any lock response.
     * The other possible lock responses get backtracking points to be explored later.
     *
     * Called when all threads that can be switched to are live-locked.
     *
     * @return true if one of the blocked lock requests was granted a response.
     */
    internal fun completePendingLockRequests(): Boolean {
        if (inReplayPhase()) return false
        val requests = blockedEvents.values.asSequence()
            .filter { it.response == null }
            .map { it.request }
            .filter { it.label.isNonReentrantLockRequest && playedFrontier[it.threadId] == it }
            .sortedBy { it.id }
            .toList()
        val completions = mutableListOf<AtomicThreadEvent>()
        for (request in requests) {
            val label = request.label as LockLabel
            val sources = synchronizationCandidates(request)
                .filter { it in playedFrontier }
                .filter { source ->
                    val consumers = execution.asSequence() + blockedEvents.values.mapNotNull { it.response }.asSequence()
                    consumers.none {
                        it.label.isNonReentrantLockResponse &&
                        (it.label as LockLabel).mutexID == label.mutexID &&
                        it.locksFrom == source
                    }
                }
                .sortedByDescending { execution.lockOrder.position(it) }
                .toList()
            for (source in sources) {
                val responseLabel = label.synchronize(source.label) ?: continue
                val response = createEvent(
                    iThread = request.threadId,
                    label = responseLabel,
                    parent = request,
                    dependencies = listOf(source),
                    visit = false,
                    appendInPlace = true,
                ) ?: continue
                completions.add(response)
            }
        }
        // Every contender's alternative observes the same unreserved prefix.
        val chosen = completions.firstOrNull() ?: return false
        takeUnblockingResponse(chosen)
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

    fun addObjectAllocationEvent(iThread: Int, value: OpaqueValue, objectID: ObjectNumber): AtomicThreadEvent {
        tryReplayEvent(iThread)?.let { event ->
            check(event.label is ObjectAllocationLabel)
            check((event.label as ObjectAllocationLabel).className == value.unwrap().javaClass.simpleName)
            // NOTE: Currently, the objectID value represents the "suggested" objectID from the object tracker.
            //  The objectID is incremented each time a new object is added to the object tracker and this value is not
            //  reset between invocations.
            //  When replaying events, we need to keep the old objectID, which needs to be at least smaller or equal
            //  to the new objectID suggested by the object tracker.
            check(event.label.objectID <= objectID)
            addEventToCurrentExecution(event)
            return event
        }
        val label = ObjectAllocationLabel(
            objectID = objectID,
            className = value.unwrap().javaClass.simpleName,
            memoryInitializer = { location ->
                val initValue = memoryInitializer(location)
                eventStructureObjectTracker.getOrRegisterValueID(location.type, initValue)
            },
        )
        val parent = playedFrontier[iThread]
        val dependencies = listOf<AtomicThreadEvent>()
        return createEvent(iThread, label, parent, dependencies)!!.also { event ->
            // TODO: assumes object is already allocated
            // val entry = RegistryObjectEntry(id, value, event)
            // objectRegistry.register(entry)
            addEventToCurrentExecution(event)
        }
    }

    fun addWriteEvent(iThread: Int, codeLocation: Int, location: MemoryLocation, memoryOrder: MemoryOrdering, value: ValueID,
                      readModifyWriteDescriptor: ReadModifyWriteDescriptor? = null): AtomicThreadEvent {
        val labelType =
            if (readModifyWriteDescriptor != null) LabelType.ReadModifyWriteAccess else LabelType.WriteAccess
        val label = WriteAccessLabel(
            location = location,
            writeValue = value, // TODO: change API of other methods to also take ValueID
            readModifyWriteDescriptor = readModifyWriteDescriptor,
            codeLocation = codeLocation,
            memoryOrdering = memoryOrderOverride(memoryOrder, labelType)
        )
        return addSendEvent(iThread, label)
    }

    fun addReadRequest(iThread: Int, codeLocation: Int, location: MemoryLocation, memoryOrder: MemoryOrdering,
                       readModifyWriteDescriptor: ReadModifyWriteDescriptor? = null): AtomicThreadEvent {
        // we create a read-request event with an unknown (null) value,
        // value will be filled later in the read-response event
        val labelType =
            if (readModifyWriteDescriptor != null) LabelType.ReadModifyWriteAccess else LabelType.ReadAccess
        val label = ReadAccessLabel(
            kind = LabelKind.Request,
            location = location,
            readValue = NULL_OBJECT_NUMBER.toLong(),
            readModifyWriteDescriptor = readModifyWriteDescriptor,
            codeLocation = codeLocation,
            memoryOrdering = memoryOrderOverride(memoryOrder, labelType)
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
        return responseEvent
    }

    fun addFenceEvent(iThread: Int, memoryOrder: MemoryOrdering) {
        val label = FenceLabel(memoryOrder)
        addSendEvent(iThread, label)
    }

    fun addFreezeEvent(iThread: Int, objectID: ObjectNumber, locations: Set<MemoryLocation>) {
        if (locations.isEmpty()) return
        val unfrozen = locations.toMutableSet()
        var predecessor = playedFrontier[iThread]
        while (predecessor != null) {
            val label = predecessor.label
            if (label is ObjectAllocationLabel && label.objectID == objectID) break
            if (label is FreezeLabel && label.objectID == objectID) unfrozen.removeAll(label.locations)
            predecessor = predecessor.parent
        }
        // Delegating constructors and caller-side allocation callbacks must not refreeze fields.
        if (unfrozen.isNotEmpty()) {
            addSendEvent(iThread, FreezeLabel(objectID, unfrozen))
        }
    }

    fun addLockRequestEvent(iThread: Int, mutex: Any,
                            isReentry: Boolean = false, reentrancyDepth: Int = 1,
                            isSynthetic: Boolean = false): AtomicThreadEvent {
        val label = LockLabel(
            kind = LabelKind.Request,
            mutexID = eventStructureObjectTracker.registerObjectIfAbsent(mutex).objectNumber,
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

    fun addUnlockEvent(iThread: Int, mutex: Any,
                       isReentry: Boolean = false, reentrancyDepth: Int = 1,
                       isSynthetic: Boolean = false): AtomicThreadEvent {
        val label = UnlockLabel(
            mutexID = eventStructureObjectTracker.registerObjectIfAbsent(mutex).objectNumber,
            isReentry = isReentry,
            reentrancyDepth = reentrancyDepth,
            isSynthetic = isSynthetic,
        )
        return addSendEvent(iThread, label)
    }

    fun addWaitRequestEvent(iThread: Int, mutex: Any): AtomicThreadEvent {
        val label = WaitLabel(
            kind = LabelKind.Request,
            mutexID = eventStructureObjectTracker.registerObjectIfAbsent(mutex).objectNumber,
        )
        return addRequestEvent(iThread, label)

    }

    fun addWaitResponseEvent(waitRequest: AtomicThreadEvent): AtomicThreadEvent? {
        require(waitRequest.label.isRequest && waitRequest.label is WaitLabel)
        return addResponseEvents(waitRequest).first
    }

    fun addNotifyEvent(iThread: Int, mutex: Any, isBroadcast: Boolean): AtomicThreadEvent {
        // TODO: we currently ignore isBroadcast flag and handle `notify` similarly as `notifyAll`.
        //   It is correct wrt. Java's semantics, since `wait` can wake-up spuriously according to the spec.
        //   Thus multiple wake-ups due to single notify can be interpreted as spurious.
        //   However, if one day we will want to support wait semantics without spurious wake-ups
        //   we will need to revisit this.
        val label = NotifyLabel(
            mutexID = eventStructureObjectTracker.registerObjectIfAbsent(mutex).objectNumber,
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

    private fun resetReadCodeLocationsCounter(iThread: Int) {
        // reset all code-locations counters of the given thread
        readCodeLocationsCounter.keys.retainAll { (tid, _) -> tid != iThread }
    }

    // NOTE: In the case of sequential consistency, to make sure that
    // happensBeforeOrder === causalityOrder, we change the memory ordering of
    // reads to VOLATILE, effectively treating every read/write as sequentially-consistent (aka `volatile`).
    // In the case of release-acquire, we change the memory ordering of reads to ACQUIRE and writes to RELEASE,
    // treating every read/write as release-acquire, as `ReleaseAcquireConsistencyChecker` assumes.
    // Under release-acquire, both RMW parts become VOLATILE, the only ordering that is both acquire and release;
    // the read and write parts of a read-modify-write are passed with the `ReadModifyWriteAccess` label type.
    // Under relaxed Java, plain RMWs become OPAQUE on both sides,
    // imposing read-read coherence as well as atomic write coherence.
    private fun memoryOrderOverride(memoryOrder: MemoryOrdering, labelType: LabelType): MemoryOrdering =
        when (memoryModel) {
            MemoryModel.SequentialConsistency -> MemoryOrdering.VOLATILE
            MemoryModel.ReleaseAcquire -> when (labelType) {
                LabelType.ReadAccess -> MemoryOrdering.ACQUIRE
                LabelType.WriteAccess -> MemoryOrdering.RELEASE
                LabelType.ReadModifyWriteAccess -> MemoryOrdering.VOLATILE
                else -> unreachable("Unexpected memory access label type: $labelType")
            }
            MemoryModel.RelaxedJava ->
                if (labelType == LabelType.ReadModifyWriteAccess && memoryOrder == MemoryOrdering.PLAIN)
                    MemoryOrdering.OPAQUE
                else memoryOrder
        }
}
