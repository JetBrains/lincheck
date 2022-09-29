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
open class Execution(
    threadEvents: Map<Int, List<Event>> = emptyMap(),
) : Collection<Event> {
    /**
     * Execution is encoded as a mapping `ThreadID -> List<Event>`
     * from thread id to a list of events belonging to this thread ordered by program-order.
     * We also assume that program order is compatible with execution order,
     * and thus events within the same thread are also ordered by execution order.
     *
     * TODO: use array instead of map?
     */
    protected val threadsEvents: MutableMap<Int, SortedArrayList<Event>> =
        threadEvents.map { (threadId, events) -> threadId to SortedArrayList(events) }.toMap().toMutableMap()

    val threads: Set<Int>
        get() = threadsEvents.keys

    override val size: Int
        get() = threadsEvents.values.sumOf { it.size }

    override fun isEmpty(): Boolean =
        threadsEvents.isEmpty()

    fun getThreadSize(iThread: Int): Int =
        threadsEvents[iThread]?.size ?: 0

    fun firstEvent(iThread: Int): Event? =
        threadsEvents[iThread]?.firstOrNull()

    fun lastEvent(iThread: Int): Event? =
        threadsEvents[iThread]?.lastOrNull()

    operator fun get(iThread: Int, Position: Int): Event? =
        threadsEvents[iThread]?.getOrNull(Position)

    override operator fun contains(element: Event): Boolean =
        threadsEvents[element.threadId]
            ?.let { events -> events[element.threadPosition] == element }
            ?: false

    override fun containsAll(elements: Collection<Event>): Boolean =
        elements.all { contains(it) }

    fun getAggregatedLabel(iThread: Int, position: Int): Pair<EventLabel, List<Event>>? {
        val threadEvents = threadsEvents[iThread]
            ?: return null
        var accumulator = threadEvents.getOrNull(position)?.label
            ?: return null
        var i = position
        while (++i < threadEvents.size) {
            accumulator = accumulator.aggregate(threadEvents[i].label)
                ?: break
        }
        return accumulator to threadEvents.subList(position, i)
    }

    override fun iterator(): Iterator<Event> =
        threadsEvents.values.asSequence().flatten().iterator()

}

class MutableExecution(
    threadEvents: Map<Int, List<Event>> = emptyMap(),
) : Execution(threadEvents) {

    fun addEvent(event: Event) {
        val threadEvents = threadsEvents.getOrPut(event.threadId) { sortedArrayListOf() }
        check(event.parent == threadEvents.lastOrNull())
        threadEvents.add(event)
    }

    fun removeLastEvent(event: Event) {
        val threadEvents = threadsEvents[event.threadId]
        check(event == threadEvents?.lastOrNull())
        threadEvents?.removeLast()
    }

}