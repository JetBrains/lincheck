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

    /**
     * Program counter - a mapping `ThreadID -> Event` from the thread id
     * to the last executed event in this thread during current exploration.
     *
     * TODO: use array instead of map?
     */
    private val programCounter: MutableMap<Int, Event> = mutableMapOf()

    private fun createEvent(lab: EventLabel, deps: List<Event>): Event {
        // TODO: check that the given thread is initialized
        val pred = programCounter[lab.threadId]!!
        // we remove dependencies which coincide with program order predecessor of the new event,
        // because we assume that as a result of synchronization the resulting event
        // has the same threadId as one of the operands and should be put immediately after it in program order.
        return Event.create(lab, pred, deps.filter { it != pred })
    }

    private fun addEvent(lab: EventLabel, deps: List<Event>): Event {
        val event = createEvent(lab, deps)
        _events.add(event)
        return event
    }

    private fun addEvents(labsWithDeps: Collection<Pair<EventLabel, List<Event>>>): List<Event> {
        val events = labsWithDeps.map { (lab, deps) -> createEvent(lab, deps) }
        _events.addAll(events)
        return events
    }

    fun addWriteEvent(iThread: Int, memoryLocationId: Int, value: Any?, typeDescriptor: String) {
        val writeLabel = MemoryAccessLabel(
            threadId = iThread,
            kind = MemoryAccessKind.Write,
            typeDesc = typeDescriptor,
            memId = memoryLocationId,
            value = value
        )
        val writeEvent = addEvent(writeLabel, listOf())
        programCounter[iThread] = writeEvent
        // TODO: check porf-acyclicity
        // TODO: check consistency of reads!
        // TODO: filter conflicting events!
        val readLabelsWithDeps = getSynchronizedLabelsWithDeps(writeEvent)
        // TODO: sort candidate events according to some strategy?
        addEvents(readLabelsWithDeps)
    }

    fun addReadEvent(iThread: Int, memoryLocationId: Int, typeDescriptor: String): Any? {
        // we first create read-request event with unknown (null) value, it will be filled in later
        val readRequestLabel = MemoryAccessLabel(
            threadId = iThread,
            kind = MemoryAccessKind.ReadRequest,
            typeDesc = typeDescriptor,
            memId = memoryLocationId,
            value = null
        )
        val readRequestEvent = addEvent(readRequestLabel, listOf())
        programCounter[iThread] = readRequestEvent
        // TODO: check consistency of reads!
        // TODO: filter conflicting events!
        val readLabelsWithDeps = getSynchronizedLabelsWithDeps(readRequestEvent)
        // TODO: sort candidate events according to some strategy?
        val readEvents = addEvents(readLabelsWithDeps)
        // TODO: use some other strategy to select the next event in the current exploration?
        val chosenRead = readEvents.lastOrNull()?.also { programCounter[iThread] = it }
        return chosenRead?.let { (it.label as MemoryAccessLabel).value }
    }

    /**
     * Returns a list of labels obtained as a result of synchronizing given event [event]
     * with the events contained in the event structure, along with the list of dependencies.
     * That is, if `e1 @ A` is the passed event labeled by `A` and
     * `e @ B` is some event in the event structures labeled by `B`, then the resulting list
     * will contain pair `(C = A \+ B, listOf(A, B))` if `C` is defined (i.e. not null).
     */
    private fun getSynchronizedLabelsWithDeps(event: Event): Collection<Pair<EventLabel, List<Event>>> =
        // TODO: instead of linear scan we should maintain an index of read/write accesses to specific memory location
        events.mapNotNull {
            val syncLab = event.label.synchronize(it.label) ?: return@mapNotNull null
            val deps = listOf(event, it)
            syncLab to deps
        }

}

class EventStructureMemoryTracker(val eventStructure: EventStructure): MemoryTracker() {

    override fun writeValue(iThread: Int, memoryLocationId: Int, value: Any?, typeDescriptor: String) =
        eventStructure.addWriteEvent(iThread, memoryLocationId, value, typeDescriptor)

    override fun readValue(iThread: Int, memoryLocationId: Int, typeDescriptor: String): Any? =
        eventStructure.addReadEvent(iThread, memoryLocationId, typeDescriptor)

}