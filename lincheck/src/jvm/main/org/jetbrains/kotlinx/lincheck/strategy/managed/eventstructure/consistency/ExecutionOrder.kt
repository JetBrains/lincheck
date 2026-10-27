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
import org.jetbrains.lincheck.util.implies
import org.jetbrains.lincheck.util.union


/**
 * Caches a candidate execution event order: a total order such that
 * replaying the execution in this order produces the same observable result as the original execution.
 * Typically, this order is a total extension of the memory-model specific partial order that
 * loosely defines a memory-model specific notion of causality.
 *
 * In particular, under the sequential consistency memory model,
 * this order represents the order in which events are executed operationally over the memory store:
 * a map from memory location to the current value of this location,
 * or, equivalently, to the latest write to this location.
 *
 * This order can be maintained incrementally during the execution construction:
 * a new event is always added to the end of the execution order list
 * (as the execution order is a total order, it can be represented as a list of events).
 * When added to the end of the execution order list, the event is checked to be
 * consistent with the previous events, it must be:
 * - for a response event - a valid response to the previous event;
 * - for a read-response event, additionally, the reads-from should be from the coherence-latest write to its location
 *   (if the coherence order is computed);
 * - for an exclusive write event - a write-part of an atomic update.
 *
 * Consistency checkers re-validate incrementally extended candidates by replay before accepting the execution.
 *
 * If the event is not a consistent extension, the order is marked inconsistent,
 * and the consistency checker is responsible for invalidating it.
 *
 * @param coherenceOrder the coherence order induced by this order, extended along with it.
 */
class ExecutionOrder(
    val execution: Execution<AtomicThreadEvent>,
    val approximation: Relation<AtomicThreadEvent>,
    val coherenceOrder: CoherenceOrder,
) : CachedRelation<AtomicThreadEvent>, ExecutionTracker<AtomicThreadEvent, Execution<AtomicThreadEvent>> {

    var computed = false
        private set

    var consistent = true
        private set

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

    override fun onAdd(event: AtomicThreadEvent) {
        if (!computed || !consistent) return
        if (!isConsistentExtension(event)) {
            consistent = false
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
            if (!consistent) break
        }
    }

    private fun isConsistentExtension(event: AtomicThreadEvent): Boolean {
        val last = _ordering.lastOrNull()
        val label = event.label
        return when {
            label is ReadAccessLabel && label.isResponse ->
                last != null && event.isValidResponse(last) &&
                coherenceOrder.let { (it.computed && it.consistent) implies { it.readsFromLatestWrite(event) }}

            label is WriteAccessLabel && label.isExclusive ->
                last != null && event.isWritePartOfAtomicUpdate(last)

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
