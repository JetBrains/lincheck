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
import org.jetbrains.lincheck.util.Computable
import org.jetbrains.lincheck.util.Relation
import org.jetbrains.lincheck.util.RelationMatrix

// TODO: what information should we display to help identify the cause of inconsistency:
//   a cycle in writes-before relation?
class ReleaseAcquireInconsistency : Inconsistency() {
    override fun toString(): String {
        return "Release/Acquire inconsistency detected"
    }
}

class ReleaseAcquireConsistencyChecker : ConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution> {

    override fun check(execution: MutableExtendedExecution): Inconsistency? {
        execution.writesBeforeOrderComputable.apply {
            initialize()
            compute()
        }
        val writesBeforeOrder = execution.writesBeforeOrderComputable.value
        return if (!writesBeforeOrder.isConsistent())
            ReleaseAcquireInconsistency()
        else null
    }

}

/**
 * Approximates coherence using happens-before constraints and indivisible read-modify-write chains.
 *
 * @param includeReadPredecessors also orders the source writes of happens-before-related reads.
 */
class WritesBeforeOrder(
    val execution: Execution<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val rmwChainsStorage: ReadModifyWriteOrder,
    val happensBefore: Relation<AtomicThreadEvent>,
    val includeReadPredecessors: Boolean = false,
) : Relation<AtomicThreadEvent>, Computable {

    private val relations: MutableMap<MemoryLocation, RelationMatrix<AtomicThreadEvent>> = mutableMapOf()

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        val location = getLocationForSameLocationWriteAccesses(x, y)
            ?: return false
        return relations[location]?.get(x, y) ?: happensBefore(x, y)
    }

    fun isConsistent(): Boolean =
        isIrreflexive()

    private fun isIrreflexive(): Boolean =
        relations.values.all { it.isIrreflexive() }

    override fun initialize() {
        for (location in memoryAccessEventIndex.locations) {
            if (!includeReadPredecessors && memoryAccessEventIndex.isWriteWriteRaceFree(location))
                continue
            initialize(location)
        }
    }

    private fun initialize(location: MemoryLocation) {
        val writes = memoryAccessEventIndex.getWrites(location)
        val enumerator = memoryAccessEventIndex.enumerator(AtomicMemoryAccessCategory.Write, location)!!
        relations[location] = RelationMatrix(writes, enumerator)
    }

    override fun reset() {
        relations.clear()
    }

    override fun compute() {
        for ((location, relation) in relations) {
            relation.apply {
                compute(location)
                transitiveClosure()
            }
        }
    }

    private fun RelationMatrix<AtomicThreadEvent>.compute(location: MemoryLocation) {
        addHappensBeforeEdges(location)
        addOverwrittenWriteEdges(location)
        if (includeReadPredecessors) {
            addReadPredecessorEdges(location)
        }
        computeReadModifyWriteChainsClosure(location)
    }

    private fun RelationMatrix<AtomicThreadEvent>.addHappensBeforeEdges(location: MemoryLocation) {
        val relation = this
        for (write1 in memoryAccessEventIndex.getWrites(location)) {
            for (write2 in memoryAccessEventIndex.getWrites(location)) {
                // Initialization precedes every write in coherence,
                // even when an access has no happens-before edge from its allocation.
                if (write1 != write2 && (write1.label.isInitializingWriteAccess() || happensBefore(write1, write2))) {
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

    private fun RelationMatrix<AtomicThreadEvent>.computeReadModifyWriteChainsClosure(location: MemoryLocation) {
        this.equivalenceClosure { event ->
            rmwChainsStorage[location, event]?.chain
        }
    }

    private fun RelationMatrix<AtomicThreadEvent>.addReadPredecessorEdges(location: MemoryLocation) {
        // Relaxed reads do not establish hb with their source writes,
        // so rf;hb constraints must also order the corresponding writes.
        val reads = memoryAccessEventIndex.getReadResponses(location)
        val writes = memoryAccessEventIndex.getWrites(location)
        for (read in reads) {
            val source = read.readsFrom
            for (write in writes) {
                if (source != write && happensBefore(read, write)) {
                    this[source, write] = true
                }
            }
            for (laterRead in reads) {
                val laterSource = laterRead.readsFrom
                if (source != laterSource && happensBefore(read, laterRead)) {
                    this[source, laterSource] = true
                }
            }
        }
    }

}
