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
import kotlin.collections.set

class Event private constructor(
    val id: EventID,
    /**
     * Event's position in a thread
     * (i.e. number of its program-order predecessors).
     */
    val threadPos : Int = 0,
    /**
     * Event's label.
     */
    val label: EventLabel = EmptyLabel(),
    /**
     * Event's predecessor in program order.
     */
    val pred: Event? = null,
    /**
     * List of event's dependencies
     * (e.g. reads-from write for a read event).
     */
    val deps: List<Event> = listOf(),
    /**
     * Vector clock to track causality relation.
     */
    val causalityClock: VectorClock<Int, Event>,
    /**
     * State of the program counter at the point when event is created.
     */
    val programCounter: Map<Int, Event>,
) {
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
            e = e.pred ?: return null
        return e
    }

    // should only be called from EventStructure
    // TODO: enforce this invariant!
    fun visit() {
        visited = true
    }

    companion object {
        private var nextId: EventID = 0

        fun create(label: EventLabel, pred: Event?, deps: List<Event>, programCounter: Map<Int, Event>): Event {
            val id = nextId++
            val threadPos = pred?.let { it.threadPos + 1 } ?: 0
            val causalityClock = deps.fold(pred?.causalityClock ?: emptyClock) { clock, event ->
                clock + event.causalityClock
            }
            return Event(id,
                threadPos = threadPos,
                label = label,
                pred = pred,
                deps = deps,
                causalityClock = causalityClock,
                programCounter = programCounter
            ).apply {
                causalityClock.update(threadId, this)
            }
        }

        val programOrder: Relation<Event> = Relation { x, y ->
            if (x.threadId != y.threadId || x.threadPos >= y.threadPos)
                false
            else (x == y.predNth(y.threadPos - x.threadPos))
        }

        val emptyClock = VectorClock<Int, Event>(PartialOrder.ofLessThan(programOrder))
    }

    override fun equals(other: Any?): Boolean {
        return (other is Event) && (id == other.id)
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

class EventStructure(initThreadId: Int) {

    val root: Event = addRootEvent(initThreadId)

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
    private val _events: ArrayList<Event> = arrayListOf(root)

    /**
     * List of events of the event structure.
     */
    val events: List<Event> = _events

    /**
     * Program counter is a mapping `ThreadID -> Event` from the thread id
     * to the last executed event in this thread during current exploration.
     *
     * TODO: use array instead of map?
     */
    private var programCounter: MutableMap<Int, Event> = mutableMapOf()

    val programOrder: Relation<Event> = Event.programOrder

    val causalityOrder: Relation<Event> = Relation { x, y ->
        y.causalityClock.observes(x.threadId, x)
    }

    fun inCurrentExploration(e: Event): Boolean {
        val pc = programCounter[e.threadId] ?: return false
        return programOrder(e, pc)
    }

    fun startNextExploration(): Boolean {
        val eventIdx = _events.indexOfLast { !it.visited }
        _events.subList(eventIdx + 1, _events.size).clear()
        if (_events.isEmpty())
            return false
        val event = _events.last().apply { visit() }
        programCounter = event.programCounter.toMutableMap()
        if (event != root)
            programCounter[event.threadId] = event
        return true
    }

    fun getThreadStartEvent(iThread: Int): Event? {
        return threadRoots[iThread]
    }

    fun isInitializedThread(iThread: Int): Boolean =
        threadRoots.contains(iThread) || iThread == ROOT_THREAD_ID

    private fun createEvent(lab: EventLabel, deps: List<Event>): Event? {
        check(lab.isThreadInitEvent() implies !isInitializedThread(lab.threadId)) {
            "Thread ${lab.threadId} is already initialized."
        }
        check(!lab.isThreadInitEvent() implies isInitializedThread(lab.threadId)) {
            "Thread ${lab.threadId} should be initialized before new events can be added to it."
        }
        // We assume that as a result of synchronization at least one of the
        // labels participating into synchronization has same thread id as the resulting label.
        // We take the maximal (by position in a thread) dependency event and
        // consider it as immediate predecessor of the newly created event.
        // If event has no dependencies, we choose as its predecessor maximal event
        // of the given thread in current exploration.
        val pred = deps
            .filter { it.threadId == lab.threadId }
            .maxWithOrNull { x, y -> x.threadPos.compareTo(y.threadPos) }
            ?: programCounter[lab.threadId]
        check(!deps.isEmpty() implies (pred != null))
        // We also remove predecessor from the list of dependencies.
        val deps = deps.filter { it != pred }
        // To prevent causality cycles to appear we check that
        // dependencies do not causally depend on predecessor.
        if (deps.any { dep -> causalityOrder(pred!!, dep) })
            return null
        return Event.create(lab, pred, deps, programCounter.toMutableMap()).also {
            if (lab.isThreadInitEvent())
                threadRoots[lab.threadId] = it
        }
    }

    private fun addEvent(lab: EventLabel, deps: List<Event>): Event? =
        createEvent(lab, deps)?.also { _events.add(it) }

    private fun addEvents(labsWithDeps: Collection<Pair<EventLabel, List<Event>>>): List<Event> =
        labsWithDeps
            .mapNotNull { (lab, deps) -> createEvent(lab, deps) }
            .also { _events.addAll(it) }

    private fun addRootEvent(initThreadId: Int): Event {
        // we do not mark root event as visited purposefully;
        // this is just a trick to make first call to `startNextExploration`
        // to pick the root event as the next event to explore from.
        val label = ThreadForkLabel(
            threadId = ROOT_THREAD_ID,
            setOf(initThreadId)
        )
        return addEvent(label, listOf())!!
    }

    private fun addRequestEvent(lab: EventLabel): Event {
        require(lab.isRequest)
        return addEvent(lab, listOf())!!.also {
            it.visit()
            programCounter[lab.threadId] = it
        }
    }

    private fun addResponseEvents(requestEvent: Event): Pair<Event?, List<Event>> {
        require(requestEvent.label.isRequest)
        val responseEvents = addEvents(getSynchronizedLabelsWithDeps(requestEvent))
        // TODO: use some other strategy to select the next event in the current exploration?
        // TODO: check consistency of chosen event!
        val chosenEvent = responseEvents.lastOrNull()?.also {
            it.visit()
            programCounter[requestEvent.threadId] = it
        }
        return (chosenEvent to responseEvents)
    }

    private fun addTotalEvent(lab: EventLabel): Event {
        require(lab.isTotal)
        return addEvent(lab, listOf())!!.also {
            it.visit()
            programCounter[lab.threadId] = it
            addEvents(getSynchronizedLabelsWithDeps(it))
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

    fun addWriteEvent(iThread: Int, memoryLocationId: Int, value: Any?, typeDescriptor: String): Event {
        val label = MemoryAccessLabel(
            threadId = iThread,
            accessKind = MemoryAccessKind.Write,
            typeDesc = typeDescriptor,
            memId = memoryLocationId,
            value = value
        )
        return addTotalEvent(label)
    }

    fun addReadEvent(iThread: Int, memoryLocationId: Int, typeDescriptor: String): Event? {
        // we first create read-request event with unknown (null) value,
        // value will be filled later in read-response event
        val label = MemoryAccessLabel(
            threadId = iThread,
            accessKind = MemoryAccessKind.ReadRequest,
            typeDesc = typeDescriptor,
            memId = memoryLocationId,
            value = null
        )
        val requestEvent = addRequestEvent(label)
        val (responseEvent, responseEvents) = addResponseEvents(requestEvent)
        return responseEvent
    }

    /**
     * Returns a list of labels obtained as a result of synchronizing given event [event]
     * with the events contained in the current exploration, along with the list of dependencies.
     * That is, if `e1 @ A` is the passed event labeled by `A` and
     * `e2 @ B` is some event in the event structures labeled by `B`, then the resulting list
     * will contain pair `(C = A \+ B, listOf(e1, e2))` if `C` is defined (i.e. not null).
     */
    private fun getSynchronizedLabelsWithDeps(event: Event): Collection<Pair<EventLabel, List<Event>>> {
        // TODO: instead of linear scan we should maintain an index of read/write accesses to specific memory location
        val candidateEvents = events.filter { inCurrentExploration(it) }
        when (event.label.synchKind) {
            SynchronizationKind.Binary -> {
                // TODO: sort candidate labels according to some strategy?
                return candidateEvents.mapNotNull {
                    val syncLab = event.label.synchronize(it.label) ?: return@mapNotNull null
                    val deps = listOf(event, it)
                    syncLab to deps
                }
            }
            SynchronizationKind.Barrier -> {
                val (syncLab, deps) = candidateEvents.fold(event.label to listOf(event)) { (lab, deps), candidateEvent ->
                    val resultLabel = candidateEvent.label.synchronize(lab)
                    if (resultLabel != null)
                        (resultLabel to deps + candidateEvent)
                    else (lab to deps)
                }
                return when {
                    syncLab.isCompetedResponse -> listOf(syncLab to deps)
                    else -> emptyList()
                }
            }
        }
    }
}

class EventStructureMemoryTracker(val eventStructure: EventStructure): MemoryTracker() {

    override fun writeValue(iThread: Int, memoryLocationId: Int, value: Any?, typeDescriptor: String) {
        eventStructure.addWriteEvent(iThread, memoryLocationId, value, typeDescriptor)
    }

    override fun readValue(iThread: Int, memoryLocationId: Int, typeDescriptor: String): Any? {
        return eventStructure.addReadEvent(iThread, memoryLocationId, typeDescriptor)?.let {
            (it.label as MemoryAccessLabel).value
        }
    }

}

// auxiliary ghost thread used only to create root event of the event structure
private const val ROOT_THREAD_ID = -1