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

typealias EventID = Int

abstract class EventLabel

class EmptyLabel: EventLabel()

data class ReadLabel(
        val typeDesc: String,
        val memId: Int,
        val value: Any?
): EventLabel()

data class WriteLabel(
        val typeDesc: String,
        val memId: Int,
        val value: Any?
) : EventLabel()

class Event private constructor(
        val id: EventID,
        /**
         * Event's thread.
         */
        val threadId : Int = 0,
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
     */
    val children: MutableList<Event> = mutableListOf()

    companion object {
        private var nextId: EventID = 0

        fun create(
                threadId: Int,
                threadPos: Int,
                label: EventLabel,
                pred: Event?,
                deps: List<Event>
        ): Event {
            val id = nextId++
            return Event(id,
                    threadId = threadId,
                    threadPos = threadPos,
                    label = label,
                    pred = pred,
                    deps = deps,
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        return (other is Event) && (id == other.id)
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}