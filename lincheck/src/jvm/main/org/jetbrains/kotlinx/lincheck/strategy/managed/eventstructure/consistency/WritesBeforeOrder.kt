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
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.lincheck.util.CachedRelation
import org.jetbrains.lincheck.util.Relation
import org.jetbrains.lincheck.util.RelationMatrix

/**
 * Writes-before order.
 *
 * Writes-before order is a partial order on the same-location writes,
 * defining the order in which writes to the same memory location must be performed.
 * It is an under-approximation of the total coherence order (see [CoherenceOrder]).
 *
 * @property memoryAccessEventIndex the index of the execution's memory accesses, grouped by location.
 * @property rmwChainsStorage the read-modify-write chains; each chain's writes share their writes-before edges,
 *   so that no other write can be ordered in the middle of a chain.
 *   Its own cached state is computed by [compute].
 * @property happensBefore the happens-before order the writes-before order is derived from.
 */
class WritesBeforeOrder(
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val rmwChainsStorage: ReadModifyWriteOrder,
    val happensBefore: Relation<AtomicThreadEvent>,
    val memoryModel: MemoryModel,
) : CachedRelation<AtomicThreadEvent>, ExecutionTracker<AtomicThreadEvent, Execution<AtomicThreadEvent>> {

    private var computed = false

    var consistent = true
        private set

    private val relations: MutableMap<MemoryLocation, RelationMatrix<AtomicThreadEvent>> = mutableMapOf()

    override fun onAdd(event: AtomicThreadEvent) {
        invalidate()
    }

    override fun onReset(execution: Execution<AtomicThreadEvent>) {
        invalidate()
    }

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        val location = getLocationForSameLocationWriteAccesses(x, y)
            ?: return false
        return relations[location]?.get(x, y) ?: happensBefore(x, y)
    }

    private fun initialize() {
        for (location in memoryAccessEventIndex.locations) {
            initialize(location)
        }
    }

    private fun initialize(location: MemoryLocation) {
        val writes = memoryAccessEventIndex.getWrites(location)
        val enumerator = memoryAccessEventIndex.enumerator(AtomicMemoryAccessCategory.Write, location)!!
        relations[location] = RelationMatrix(writes, enumerator)
    }

    override fun invalidate() {
        computed = false
        consistent = true
        relations.clear()
    }

    override fun compute() {
        if (computed) return
        rmwChainsStorage.compute()
        initialize()
        for ((location, relation) in relations) {
            relation.apply {
                compute(location)
                transitiveClosure()
            }
        }
        consistent = relations.values.all { it.isIrreflexive() }
        computed = true
    }

    private val shouldIncludeReadFromHappensBeforeEdges: Boolean
        get() = (memoryModel == MemoryModel.RelaxedJava)

    private fun RelationMatrix<AtomicThreadEvent>.compute(location: MemoryLocation) {
        addHappensBeforeEdges(location)
        addOverwrittenWriteEdges(location)
        if (shouldIncludeReadFromHappensBeforeEdges) {
            addReadFromHappensBeforeEdges(location)
        }
        computeReadModifyWriteChainsClosure(location)
    }

    private fun RelationMatrix<AtomicThreadEvent>.addHappensBeforeEdges(location: MemoryLocation) {
        val relation = this
        for (write1 in memoryAccessEventIndex.getWrites(location)) {
            for (write2 in memoryAccessEventIndex.getWrites(location)) {
                if (write1 != write2 && (
                        happensBefore(write1, write2) ||
                        // Initialization precedes every write in coherence,
                        // even when access event has no happens-before edge from its allocation.
                        write1.label.isInitializingWriteAccess()
                )) {
                    relation[write1, write2] = true
                }
            }
        }
    }

    private fun RelationMatrix<AtomicThreadEvent>.addOverwrittenWriteEdges(location: MemoryLocation) {
        val relation = this
        for (read in memoryAccessEventIndex.getReadResponses(location)) {
            for (write in memoryAccessEventIndex.getWrites(location)) {
                if (happensBefore(write, read) && write != read.readsFrom) {
                    relation[write, read.readsFrom] = true
                }
            }
        }
    }

    private fun RelationMatrix<AtomicThreadEvent>.addReadFromHappensBeforeEdges(location: MemoryLocation) {
        // Relaxed reads do not establish hb with their source writes,
        // so rf;hb and rf;hb;rf^-1 constraints must also order the corresponding writes.
        val reads = memoryAccessEventIndex.getReadResponses(location)
        val writes = memoryAccessEventIndex.getWrites(location)
        for (read in reads) {
            val readFrom = read.readsFrom
            for (write in writes) {
                // rf;hb
                if (readFrom != write && happensBefore(read, write)) {
                    this[readFrom, write] = true
                }
            }
            for (laterRead in reads) {
                val laterReadFrom = laterRead.readsFrom
                // rf;hb;rf^-1
                if (readFrom != laterReadFrom && happensBefore(read, laterRead)) {
                    this[readFrom, laterReadFrom] = true
                }
            }
        }
    }

    private fun RelationMatrix<AtomicThreadEvent>.computeReadModifyWriteChainsClosure(location: MemoryLocation) {
        this.equivalenceClosure { event ->
            rmwChainsStorage[location, event]?.chain
        }
    }

}
