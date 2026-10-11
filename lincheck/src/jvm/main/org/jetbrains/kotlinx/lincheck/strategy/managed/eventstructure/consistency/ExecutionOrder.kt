/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.lincheck.util.CachedRelation
import org.jetbrains.lincheck.util.Relation
import org.jetbrains.lincheck.util.union


class ExecutionOrder(
    val execution: Execution<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val approximation: Relation<AtomicThreadEvent>,
) : CachedRelation<AtomicThreadEvent>, ExecutionTracker<AtomicThreadEvent, Execution<AtomicThreadEvent>> {

    var computed = false
        private set

    private var consistent = true

    private val _ordering = mutableListOf<AtomicThreadEvent>()

    val ordering: List<AtomicThreadEvent>
        get() = _ordering

    private val constraints = Relation<AtomicThreadEvent> { x, y ->
        when {
            // put wait-request before notify event
            x.label.isRequest && x.label is WaitLabel ->
                (y == execution.getResponse(x)?.notifiedBy)

            else -> false
        }
    }

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        val xPosition = _ordering.indexOf(x)
        val yPosition = _ordering.indexOf(y)
        return xPosition >= 0 && yPosition >= 0 && xPosition < yPosition
    }

    fun isConsistent(): Boolean =
        consistent

    override fun onAdd(event: AtomicThreadEvent) {
        if (!computed) return
        if (!consistent || !isConsistentExtension(event)) {
            invalidate()
            return
        }
        _ordering.add(event)
    }

    override fun onReset(execution: Execution<AtomicThreadEvent>) {
        invalidate()
        // Enumeration order is a cheap replay candidate; the checker still has to validate it.
        computed = true
        for (event in execution.enumerationOrderSorted()) {
            onAdd(event)
            if (!computed) break
        }
    }

    private fun isConsistentExtension(event: AtomicThreadEvent): Boolean {
        val last = _ordering.lastOrNull()
        val label = event.label
        return when {
            label is ReadAccessLabel && label.isResponse -> last != null && event.isValidResponse(last)
            label is WriteAccessLabel && label.isExclusive -> last != null && event.isWritePartOfAtomicUpdate(last)
            else -> true
        }
    }

    internal fun copyFrom(other: ExecutionOrder) {
        _ordering.clear()
        _ordering.addAll(other.ordering)
        consistent = other.consistent
        computed = true
    }

    override fun compute() {
        if (computed) return
        val relation = approximation union constraints
        // construct aggregated execution consisting of atomic events
        // to incorporate the atomicity constraints during the search for topological sorting
        val (aggregatedExecution, _) = execution.aggregate(ThreadAggregationAlgebra.aggregator())
        val aggregatedRelation = relation.existsLifting()
        // TODO: optimization --- we can build graph only for a subset of events, excluding:
        //  - non-blocking request events
        //  - events accessing race-free locations
        //  - what else?
        //  and then insert them back into the topologically sorted list
        val graph = aggregatedExecution.buildGraph(aggregatedRelation)
        val ordering = topologicalSorting(graph)
        computed = true
        if (ordering == null) {
            consistent = false
            return
        }
        this._ordering.addAll(ordering.flatMap { it.events })
    }

    override fun invalidate() {
        computed = false
        consistent = true
        _ordering.clear()
    }

}
