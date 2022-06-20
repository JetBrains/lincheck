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

enum class MemoryAccessKind { Read, Write }

data class MemoryAccessLabel(
    val kind: MemoryAccessKind,
    val typeDesc: String,
    val memId: Int,
    val value: Any?
): EventLabel()

/**
 * Synchronizes event label with another label passed as a parameter.
 * Note that the label on which method is called is the synchronized label,
 * i.e. non-null return value to the call `A.synchronize(B)` means that `B >> A` (B synchronizes with A).
 * For example a write label `wlab = W(x, v)` synchronizes with a read-unknown label `rlab = R(x, null)`
 * and produces the read label `lab = R(x, v)`. That is a call `rlab.synchronize(wlab)` returns `lab`.
 * TODO: the reversed order of arguments is reserved for the future, for the case when
 *   synchronized label might depend on several labels - in this case the argument to the function
 *   becomes the list of event labels. An example of such case is barrier synchronization when
 *   `wait` event might depend on several `notify` events.
 * TODO: make this method an abstract method of `EventLabel` class?
 */
fun EventLabel.synchronize(lab: EventLabel): EventLabel? {
    return when {
        (this is MemoryAccessLabel) && (lab is MemoryAccessLabel)
            && (kind == MemoryAccessKind.Read)
            && (lab.kind == MemoryAccessKind.Write)
            && (memId == lab.memId) && (value == null) ->
                // TODO: perform dynamic type-check here?
                // require(typeDesc == lab.typeDesc) { "" }
                copy(value = lab.value)
        else -> null
    }
}

fun EventLabel.synchronize(event: Event): Pair<Event, EventLabel>? {
    return synchronize(event.label)?.let { event to it }
}

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
            label: EventLabel,
            pred: Event?,
            deps: List<Event>
        ): Event {
            val id = nextId++
            val threadPos = pred?.let { it.threadPos + 1 } ?: 0
            return Event(
                id,
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
