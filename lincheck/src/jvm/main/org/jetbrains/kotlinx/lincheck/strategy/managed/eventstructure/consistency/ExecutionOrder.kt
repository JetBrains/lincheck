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

import org.jetbrains.kotlinx.lincheck.strategy.managed.MemoryLocation
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.lincheck.util.CachedRelation
import org.jetbrains.lincheck.util.Relation
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
 * A pending read request can be moved next to its response:
 * it has no memory effect or successors in the existing order.
 * When added to the end of the execution order list, the event is checked to be
 * consistent with the previous events, it must be:
 * - for a read-response event - a valid response reading from the latest write to its location in this order;
 * - for an exclusive write event - the write-part of the immediately preceding atomic read;
 * - for a wait-response event - preceded by its request and the matching notification, in that order.
 * To check the reads, the order maintains its own view of the memory store, mapping each location to its latest write.
 *
 * Consistency checkers re-validate incrementally extended candidates by replay before accepting the execution,
 * since the replay additionally checks the monitor operations.
 *
 * If the event is not a consistent extension, the order is marked inconsistent,
 * and the consistency checker is responsible for invalidating it.
 */
class ExecutionOrder(
    val execution: Execution<AtomicThreadEvent>,
    val approximation: Relation<AtomicThreadEvent>,
) : CachedRelation<AtomicThreadEvent>, ExecutionTracker<AtomicThreadEvent, Execution<AtomicThreadEvent>> {

    var computed = false
        private set

    var consistent = true
        private set

    private val _ordering = mutableListOf<AtomicThreadEvent>()

    val ordering: List<AtomicThreadEvent>
        get() = _ordering

    private val latestWrites = mutableMapOf<MemoryLocation, AtomicThreadEvent>()

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
        add(event)
    }

    private fun add(event: AtomicThreadEvent) {
        if (!isConsistentExtension(event)) {
            consistent = false
            return
        }
        _ordering.add(event)
        val label = event.label
        if (label is WriteAccessLabel) {
            latestWrites[label.location] = event
        }
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
        when {
            label is ReadAccessLabel && label.isResponse -> {
                val request = event.request!!
                if (!(event.isValidResponse(request) && readsFromLatestWrite(event))) return false
                // Normally, the request is the last event in the order, right before its response.
                // However, in some cases other events are added in between:
                // e.g., when the order is rebuilt in the enumeration order after a backward revisit,
                // the read-response is created after the write event it reads-from, and thus comes after it,
                // while the request comes before this write.
                // In such cases, it is still safe to move the request to the end of the order:
                // the read request has no memory effect, and none of the events already in the order depend on it
                // (its only successor is this response, which is not added yet).
                if (last != request) {
                    val requestWasAbsent = !_ordering.remove(request)
                    if (requestWasAbsent) return false
                    _ordering.add(request)
                }
                return true
            }

            label is WriteAccessLabel && label.isExclusive ->
                return last != null && event.isWritePartOfAtomicUpdate(last)

            label is WaitLabel && label.isResponse ->
                return this(event.request!!, event.notifiedBy)

            else -> return true
        }
    }

    private fun readsFromLatestWrite(read: AtomicThreadEvent): Boolean {
        val location = (read.label as ReadAccessLabel).location
        // before any write, the latest write is the initializing one:
        // the object allocation or the global initialization event
        return read.readsFrom == (latestWrites[location] ?: read.allocation)
    }

    internal fun copyFrom(other: ExecutionOrder) {
        _ordering.clear()
        _ordering.addAll(other.ordering)
        latestWrites.clear()
        latestWrites.putAll(other.latestWrites)
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
        // the extension checks also build the memory view of the order
        for (event in ordering.flatMap { it.events }) {
            add(event)
            if (!consistent) break
        }
    }

    override fun invalidate() {
        computed = false
        consistent = true
        _ordering.clear()
        latestWrites.clear()
    }

}
