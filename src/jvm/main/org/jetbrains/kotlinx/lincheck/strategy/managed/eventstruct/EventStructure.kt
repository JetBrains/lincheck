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

import org.jetbrains.kotlinx.lincheck.strategy.managed.*

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

    fun addEvent(iThread: Int, lab: EventLabel, deps: List<Event>) {
        // TODO: check that the given thread is initialized
        val pred = programCounter[iThread]!!
        val event = Event.create(iThread, lab, pred, deps)
        _events.add(event)
        programCounter[iThread] = event
    }

    fun addEvents(iThread: Int, labs: Collection<Pair<EventLabel, List<Event>>>): Event? {
        // TODO: check that the given thread is initialized
        val pred = programCounter[iThread]!!
        val events = labs.map { (lab, deps) -> Event.create(iThread, lab, pred, deps) }
        _events.addAll(events)
        // TODO: use some other strategy to select the next event in the current exploration?
        return events.lastOrNull()?.also { programCounter[iThread] = it }
    }

}

class EventStructMemoryTracker(val eventStructure: EventStructure): MemoryTracker() {
    override fun writeValue(iThread: Int, value: Any?, memoryLocationId: Int) {
        // TODO: fix typeDesc
        val lab = MemoryAccessLabel(MemoryAccessKind.Write, "", memoryLocationId, value)
        eventStructure.addEvent(iThread, lab, listOf())
    }

    override fun readValue(iThread: Int, memoryLocationId: Int, typeDesc: String): Any? {
        // we first create read label with unknown (null) value, it will be filled in later
        // TODO: as a possible optimization we can avoid one allocation of temporarily label object here
        //   by refactoring EventLabel.synchronize function. However, current design with
        //   EventLabel.synchronize taking just two labels is more clear and concise.
        val preLab = MemoryAccessLabel(MemoryAccessKind.Read, typeDesc, memoryLocationId, null)
        // TODO: instead of linear scan we should maintain an index of read/write accesses to specific memory location
        // TODO: check consistency of reads!
        val labs = eventStructure.events.mapNotNull {
            val lab = it.label.synchronize(preLab) ?: return@mapNotNull null
            val deps = listOf(it)
            return lab to deps
        }
        val chosenRead = eventStructure.addEvents(iThread, labs)
        return chosenRead?.let { (it.label as MemoryAccessLabel).value }
    }
}