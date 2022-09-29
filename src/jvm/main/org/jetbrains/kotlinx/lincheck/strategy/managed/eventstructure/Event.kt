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

typealias EventID = Int

class Event private constructor(
    val id: EventID,
    /**
     * Event's position in a thread
     * (i.e. number of its program-order predecessors).
     */
    val threadPosition: Int = 0,
    /**
     * Event's label.
     */
    val label: EventLabel = EmptyLabel(),
    /**
     * Event's parent in program order.
     */
    val parent: Event? = null,
    /**
     * List of event's dependencies
     * (e.g. reads-from write for a read event).
     */
    val dependencies: List<Event> = listOf(),
    /**
     * Vector clock to track causality relation.
     */
    val causalityClock: VectorClock<Int, Event>,
    /**
     * State of the execution frontier at the point when event is created.
     */
    val frontier: ExecutionFrontier,
    /**
     * Frontier of pinned events.
     */
    val pinnedEvents: ExecutionFrontier,
) : Comparable<Event> {

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
            e = e.parent ?: return null
        return e
    }

    // should only be called from EventStructure
    // TODO: enforce this invariant!
    fun visit() {
        visited = true
    }

    companion object {
        private var nextId: EventID = 0

        fun create(
            label: EventLabel,
            parent: Event?,
            dependencies: List<Event>,
            frontier: ExecutionFrontier,
            pinnedEvents: ExecutionFrontier
        ): Event {
            val id = nextId++
            val threadPosition = parent?.let { it.threadPosition + 1 } ?: 0
            val causalityClock = dependencies.fold(parent?.causalityClock?.copy() ?: emptyClock()) { clock, event ->
                clock + event.causalityClock
            }
            return Event(id,
                threadPosition = threadPosition,
                label = label,
                parent = parent,
                dependencies = dependencies,
                causalityClock = causalityClock,
                frontier = frontier,
                pinnedEvents = pinnedEvents,
            ).apply {
                causalityClock.update(threadId, this)
                frontier[threadId] = this
                pinnedEvents.merge(causalityClock.toFrontier())
            }
        }
    }

    val readsFrom: Event by lazy {
        require(label is MemoryAccessLabel && label.isRead && !label.isRequest)
        require(dependencies.isNotEmpty())
        dependencies.first().also {
            // TODO: make `isSynchronized` method to check for labels' compatibility
            //  according to synchronization algebra (e.g. write/read reads-from compatibility)
            check((it.label is InitializationLabel) ||
                  (it.label is MemoryAccessLabel && it.label.isWrite &&
                   it.label.memId == label.memId))
        }
    }

    val exclusiveReadPart: Event by lazy {
        require(label is AtomicMemoryAccessLabel && label.isWrite && label.isExclusive)
        require(parent != null)
        parent.also {
            check(it.label is AtomicMemoryAccessLabel
                && it.label.isRead && !it.label.isRequest
                && it.label.memId == label.memId
                && it.label.isExclusive
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        return (other is Event) && (id == other.id)
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun compareTo(other: Event): Int {
        return id.compareTo(other.id)
    }

    override fun toString(): String {
        return "#${id}: [${threadId}, ${threadPosition}] $label"
    }
}

val programOrder: PartialOrder<Event> = PartialOrder.ofLessThan { x, y ->
    if (x.threadId != y.threadId || x.threadPosition >= y.threadPosition)
        false
    else (x == y.predNth(y.threadPosition - x.threadPosition))
}

val causalityOrder: PartialOrder<Event> = PartialOrder.ofLessOrEqual { x, y ->
    y.causalityClock.observes(x.threadId, x)
}

val externalCausalityCovering: Covering<Event> = Covering { y ->
    y.dependencies
}

fun emptyClock() = VectorClock<Int, Event>(programOrder)