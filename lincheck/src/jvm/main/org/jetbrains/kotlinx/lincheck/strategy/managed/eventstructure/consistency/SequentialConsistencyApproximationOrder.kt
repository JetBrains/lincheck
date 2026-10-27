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

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.lincheck.util.CachedRelation
import org.jetbrains.lincheck.util.Relation
import org.jetbrains.lincheck.util.RelationMatrix


/**
 * Sequential consistency approximation order:
 * a partial order that under-approximates every sequentially consistent execution order of the execution.
 *
 * Starting from the [memoryAccessOrder] (e.g. happens-before united with extended coherence),
 * computes the least relation closed under transitivity and the following per-location saturation rules,
 * for a read `r`, the write `rf(r)` it reads-from, and another write `w` to the same location:
 * - if `w` is ordered before `r`, then `w` is ordered before `rf(r)`;
 * - if `rf(r)` is ordered before `w`, then `r` is ordered before `w`.
 *
 * Any sequentially consistent execution order respecting the reads-from relation must contain these edges,
 * so a cycle in the saturated relation witnesses a sequential consistency violation (see [consistent]).
 * Otherwise, the relation can be used as an initial approximation to guide the search
 * for a sequentially consistent execution order (e.g. as the base of the [ExecutionOrder]).
 *
 * The saturation rules are sound only under sequential consistency:
 * transitivity combines the edges across different memory locations,
 * thus the relation must not be used to check weaker memory models, such as release-acquire.
 *
 * Also known as the saturated happens-before relation, introduced in the paper
 * [Optimal Stateless Model Checking for Reads-From Equivalence under Sequential Consistency](https://dl.acm.org/doi/pdf/10.1145/3360576).
 *
 * @param memoryAccessOrder the initial approximation to saturate;
 *   its own cached state must be computed by the caller before [compute].
 */
class SequentialConsistencyApproximationOrder(
    val execution: Execution<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val memoryAccessOrder: Relation<AtomicThreadEvent>,
) : CachedRelation<AtomicThreadEvent>, ExecutionTracker<AtomicThreadEvent, Execution<AtomicThreadEvent>> {

    private var computed = false
    var consistent = true
        private set

    private var relation: RelationMatrix<AtomicThreadEvent>? = null

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean =
        relation?.invoke(x, y) ?: false

    override fun onAdd(event: AtomicThreadEvent) {
        invalidate()
    }

    override fun onReset(execution: Execution<AtomicThreadEvent>) {
        invalidate()
    }

    override fun compute() {
        if (computed) return
        initialize()
        val relation = this.relation!!
        relation.add(memoryAccessOrder)
        relation.fixpoint {
            // TODO: maybe we can remove this check without affecting performance?
            if (!isIrreflexive()) {
                consistent = false
                return@fixpoint
            }
            coherenceClosure()
            transitiveClosure()
        }
        computed = true
    }

    private fun initialize() {
        // TODO: optimize -- build the relation only for write and read-response events
        relation = RelationMatrix(execution, execution.buildEnumerator())
    }

    override fun invalidate() {
        computed = false
        consistent = true
        relation = null
    }

    private fun RelationMatrix<AtomicThreadEvent>.coherenceClosure() {
        for (location in memoryAccessEventIndex.locations) {
            coherenceClosure(location)
        }
    }

    private fun RelationMatrix<AtomicThreadEvent>.coherenceClosure(location: MemoryLocation) {
        val relation = this
        for (read in memoryAccessEventIndex.getReadResponses(location)) {
            for (write in memoryAccessEventIndex.getWrites(location)) {
                if (relation(write, read) && write != read.readsFrom) {
                    relation[write, read.readsFrom] = true
                }
                if (relation(read.readsFrom, write)) {
                    relation[read, write] = true
                }
            }
        }
    }

}
