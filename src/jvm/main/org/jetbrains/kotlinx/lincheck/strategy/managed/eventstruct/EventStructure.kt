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
    val deps: List<Event> = listOf()
) {
    /**
     * List of event's children in program order.
     *
     * TODO: this should be carefully garbage collected upon restriction of event structure!
     */
    val children: MutableList<Event> = mutableListOf()

    val threadId: Int = label.threadId

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

    companion object {
        private var nextId: EventID = 0

        fun create(label: EventLabel, pred: Event?, deps: List<Event>): Event {
            val id = nextId++
            val threadPos = pred?.let { it.threadPos + 1 } ?: 0
            val event = Event(id,
                threadPos = threadPos,
                label = label,
                pred = pred,
                deps = deps,
            )
            pred?.also { it.children.add(event) }
            return event
        }
    }

    override fun equals(other: Any?): Boolean {
        return (other is Event) && (id == other.id)
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

class EventStructure {


    // TODO: this pattern is covered by explicit backing fields KEEP
    //   https://github.com/Kotlin/KEEP/issues/278
    private val _events: ArrayList<Event> = arrayListOf()

    /**
     * List of events of the event structure.
     */
    val events: List<Event> = _events

    val programOrder: Relation<Event> = relation { x, y ->
        if ((x.threadId != y.threadId) || x.threadPos >= y.threadPos)
            false
        else (x == y.predNth(y.threadPos - x.threadPos))
    }

    val causalityOrder: Relation<Event> = relation { x, y ->
        TODO()
    }

    /**
     * Program counter - a mapping `ThreadID -> Event` from the thread id
     * to the last executed event in this thread during current exploration.
     *
     * TODO: use array instead of map?
     */
    private val programCounter: MutableMap<Int, Event> = mutableMapOf()

    fun inCurrentExploration(e: Event): Boolean {
        val pc = programCounter[e.threadId] ?: return false
        return programOrder(e, pc)
    }

    private fun createEvent(lab: EventLabel, deps: List<Event>): Event? {
        // TODO: check that the given thread is initialized
        // We assume that as a result of synchronization at least one of the
        // labels participating into synchronization has same thread id as the resulting label.
        // We take the maximal (by position in a thread) dependency event and
        // consider it as immediate predecessor of the newly created event.
        // If event has no dependencies, we choose as its predecessor maximal event
        // of the given thread in current exploration.
        val pred = deps
            .filter { it.threadId == lab.threadId }
            .maxWithOrNull { x, y -> x.threadPos.compareTo(y.threadPos) }
            ?: programCounter[lab.threadId]!!
        // We also remove predecessor from the list of dependencies.
        val deps = deps.filter { it != pred }
        // To prevent causality cycles to appear we check that
        // dependencies do not causally depend on predecessor.
        if (deps.any { dep -> causalityOrder(pred, dep) })
            return null
        return Event.create(lab, pred, deps)
    }

    private fun addEvent(lab: EventLabel, deps: List<Event>): Event? =
        createEvent(lab, deps)?.also { _events.add(it) }

    private fun addEvents(labsWithDeps: Collection<Pair<EventLabel, List<Event>>>): List<Event> =
        labsWithDeps
            .mapNotNull { (lab, deps) -> createEvent(lab, deps) }
            .also { _events.addAll(it) }

    fun addWriteEvent(iThread: Int, memoryLocationId: Int, value: Any?, typeDescriptor: String) {
        val writeLabel = MemoryAccessLabel(
            threadId = iThread,
            kind = MemoryAccessKind.Write,
            typeDesc = typeDescriptor,
            memId = memoryLocationId,
            value = value
        )
        val writeEvent = addEvent(writeLabel, listOf())!!
        programCounter[iThread] = writeEvent
        val readLabelsWithDeps = getSynchronizedLabelsWithDeps(writeEvent)
        addEvents(readLabelsWithDeps)
    }

    fun addReadEvent(iThread: Int, memoryLocationId: Int, typeDescriptor: String): Any? {
        // we first create read-request event with unknown (null) value,
        // value will be filled later in read-response event
        val readRequestLabel = MemoryAccessLabel(
            threadId = iThread,
            kind = MemoryAccessKind.ReadRequest,
            typeDesc = typeDescriptor,
            memId = memoryLocationId,
            value = null
        )
        val readRequestEvent = addEvent(readRequestLabel, listOf())!!
        programCounter[iThread] = readRequestEvent
        val readLabelsWithDeps = getSynchronizedLabelsWithDeps(readRequestEvent)
        val readEvents = addEvents(readLabelsWithDeps)
        // TODO: use some other strategy to select the next event in the current exploration?
        val chosenRead = readEvents.lastOrNull()?.also { programCounter[iThread] = it }
        // TODO: check consistency of chosen read!
        return chosenRead?.let { (it.label as MemoryAccessLabel).value }
    }

    /**
     * Returns a list of labels obtained as a result of synchronizing given event [event]
     * with the events contained in the current exploration, along with the list of dependencies.
     * That is, if `e1 @ A` is the passed event labeled by `A` and
     * `e2 @ B` is some event in the event structures labeled by `B`, then the resulting list
     * will contain pair `(C = A \+ B, listOf(e1, e2))` if `C` is defined (i.e. not null).
     */
    private fun getSynchronizedLabelsWithDeps(event: Event): Collection<Pair<EventLabel, List<Event>>> =
        // TODO: instead of linear scan we should maintain an index of read/write accesses to specific memory location
        events
            .filter { inCurrentExploration(it) }
            .mapNotNull {
                val syncLab = event.label.synchronize(it.label) ?: return@mapNotNull null
                val deps = listOf(event, it)
                syncLab to deps
            }
            // TODO: sort candidate events according to some strategy?
}

class EventStructureMemoryTracker(val eventStructure: EventStructure): MemoryTracker() {

    override fun writeValue(iThread: Int, memoryLocationId: Int, value: Any?, typeDescriptor: String) =
        eventStructure.addWriteEvent(iThread, memoryLocationId, value, typeDescriptor)

    override fun readValue(iThread: Int, memoryLocationId: Int, typeDescriptor: String): Any? =
        eventStructure.addReadEvent(iThread, memoryLocationId, typeDescriptor)

}