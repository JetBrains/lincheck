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
 * ExecutionFrontier represents a frontier of an execution,
 * that is the set of program-order maximal events of the execution.
 */
class ExecutionFrontier(frontier: Map<Int, Event> = emptyMap()) {

    /**
     * Frontier is encoded as a mapping `ThreadID -> Event` from the thread id
     * to the last executed event in this thread in the given execution.
     *
     * TODO: use array instead of map?
     */
    private val frontier: MutableMap<Int, Event> = frontier.toMutableMap()

    fun update(event: Event) {
        check(event.parent == frontier[event.threadId])
        frontier[event.threadId] = event
    }

    fun getPosition(iThread: Int): Int =
        frontier[iThread]?.threadPosition ?: -1

    fun getNextPosition(iThread: Int): Int =
        1 + getPosition(iThread)

    operator fun get(iThread: Int): Event? =
        frontier[iThread]

    operator fun set(iThread: Int, event: Event) {
        check(iThread == event.threadId)
        // TODO: properly document this precondition
        //  (i.e. we expect frontier to be updated to some offspring of frontier's execution)
        check(programOrder.nullOrLessOrEqual(event.parent, frontier[iThread]))
        frontier[iThread] = event
    }

    operator fun contains(event: Event): Boolean {
        val lastEvent = frontier[event.threadId] ?: return false
        return programOrder.lessOrEqual(event, lastEvent)
    }

    fun copy(): ExecutionFrontier =
        ExecutionFrontier(frontier)

    fun toExecution(): MutableExecution {
        return MutableExecution(frontier.map { (threadId, lastEvent) ->
            var event: Event? = lastEvent
            val events = arrayListOf<Event>()
            while (event != null) {
                events.add(event)
                event = event.parent
            }
            threadId to events.apply { reverse() }
        }.toMap())
    }

}