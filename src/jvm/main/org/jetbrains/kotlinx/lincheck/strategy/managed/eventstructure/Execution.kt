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

/**
 * Execution represents a set of events belonging to single program's execution.
 */
class Execution(
    threadEvents: Map<Int, List<Event>> = emptyMap(),
    val ghostThread: Int = GHOST_THREAD_ID,
) {
    /**
     * Execution is encoded as a mapping `ThreadID -> List<Event>`
     * from thread id to a list of events belonging to this thread ordered by program-order.
     * We also assume that program order is compatible with execution order,
     * and thus events within the same thread are also ordered by execution order.
     *
     * TODO: use array instead of map?
     */
    private val threadsEvents: MutableMap<Int, SortedArrayList<Event>> =
        threadEvents.map { (threadId, events) -> threadId to SortedArrayList(events) }.toMap().toMutableMap()

    val threads: Set<Int>
        get() = threadsEvents.keys

    fun addEvent(event: Event) {
        val threadEvents = threadsEvents.getOrPut(event.threadId) { sortedArrayListOf() }
        check(event.parent == threadEvents.lastOrNull())
        threadEvents.add(event)
    }

    fun getThreadSize(iThread: Int): Int =
        threadsEvents[iThread]?.size ?: 0

    fun firstEvent(iThread: Int): Event? =
        threadsEvents[iThread]?.firstOrNull()

    fun lastEvent(iThread: Int): Event? =
        threadsEvents[iThread]?.lastOrNull()

    operator fun get(iThread: Int, Position: Int): Event? =
        threadsEvents[iThread]?.getOrNull(Position)

    operator fun contains(event: Event): Boolean =
        threadsEvents[event.threadId]?.let { events -> event in events } ?: false

    fun getAggregatedEvent(iThread: Int, position: Int): Pair<Event, List<Event>>? {
        val threadEvents = threadsEvents[iThread] ?: return null
        val (event, nextPosition) = threadEvents.getSquashed(position, Event::aggregate) ?: return null
        return event to threadEvents.subList(position, nextPosition)
    }

    // TODO: do not use it, not ready yet!
    fun aggregated(): Execution = Execution(
        // TODO: most likely, we should also compute remapping of events ---
        //   a function from an old atomic event to new compound event
        threadsEvents.mapValues { (_, events) ->
            // TODO: usage of `squash` function here most likely
            //   violates an invariant that events are sorted in the list
            //   according to the order of their IDs.
            events.squash(Event::aggregate)
            // TODO: we should remap dependencies to their new compound counterparts at the end
        }
    )

}