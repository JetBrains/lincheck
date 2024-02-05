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

// TODO: what information should we display to help identify the cause of inconsistency:
//   a cycle in writes-before relation?
class ReleaseAcquireInconsistency() : Inconsistency() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Release/Acquire inconsistency detected"
    }
}

class ReleaseAcquireConsistencyWitness(
    val executionIndex: AtomicMemoryAccessEventIndex,
    val rmwChainsStorage: ReadModifyWriteChainsStorage,
    val writesBefore: WritesBeforeRelation,
)

class ReleaseAcquireConsistencyChecker : ConsistencyChecker<AtomicThreadEvent, ReleaseAcquireConsistencyWitness> {

    override fun check(execution: Execution<AtomicThreadEvent>): ConsistencyVerdict<ReleaseAcquireConsistencyWitness> {
        val executionIndex = MutableAtomicMemoryAccessEventIndex()
            .apply { index(execution) }
        val rmwChainsStorage = ReadModifyWriteChainsStorage()
            .apply { compute(execution) }
        if (!rmwChainsStorage.isConsistent()) {
            // TODO: should return RMW-atomicity violation instead
            return ReleaseAcquireInconsistency()
        }
        val writesBeforeRelation = WritesBeforeRelation(execution, executionIndex, rmwChainsStorage, causalityOrder.lessThan)
            .apply {
                initialize()
                compute()
            }
        return if (!writesBeforeRelation.isIrreflexive())
            ReleaseAcquireInconsistency()
        else
            ConsistencyWitness(ReleaseAcquireConsistencyWitness(
                executionIndex = executionIndex,
                rmwChainsStorage = rmwChainsStorage,
                writesBefore = writesBeforeRelation,
            ))
    }

}

class WritesBeforeRelation(
    val execution: Execution<AtomicThreadEvent>,
    val executionIndex: AtomicMemoryAccessEventIndex,
    val rmwChainsStorage: ReadModifyWriteChainsStorage,
    val happensBefore: Relation<AtomicThreadEvent>,
) : Relation<AtomicThreadEvent> {

    private val relations: MutableMap<MemoryLocation, RelationMatrix<AtomicThreadEvent>> = mutableMapOf()

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        val location = getLocationForSameLocationWriteAccesses(x, y)
            ?: return false
        return relations[location]?.get(x, y) ?: false
    }

    fun isIrreflexive(): Boolean =
        relations.values.all { it.isIrreflexive() }

    fun initialize() {
        for (location in executionIndex.locations) {
            initialize(location)
        }
    }

    private fun initialize(location: MemoryLocation) {
        val writes = executionIndex.getWrites(location)
        val enumerator = executionIndex.enumerator(AtomicMemoryAccessCategory.Write, location)!!
        relations[location] = RelationMatrix(writes, enumerator)
    }

    fun compute() {
        for ((location, relation) in relations) {
            relation.apply {
                compute(location)
                transitiveClosure()
            }
        }
    }

    private fun compute(location: MemoryLocation) {
        addHappensBeforeEdges(location)
        addOverwrittenWriteEdges(location)
        computeReadModifyWriteChainsClosure(location)
    }

    private fun addHappensBeforeEdges(location: MemoryLocation) {
        val relation = relations[location]!!
        for (write1 in executionIndex.getWrites(location)) {
            for (write2 in executionIndex.getWrites(location)) {
                // TODO: also add `rf^?;hb` edges (it is required for any model where `causalityOrder < happensBefore`)
                if (happensBefore(write1, write2) && write1 != write2) {
                    relation[write1, write2] = true
                }
            }
        }
    }

    private fun addOverwrittenWriteEdges(location: MemoryLocation) {
        val relation = relations[location]!!
        for (read in executionIndex.getReadResponses(location)) {
            for (write in executionIndex.getWrites(location)) {
                // TODO: change this check from `(w,r) \in hb` to `(w,r) \in rf^?;hb`
                if (happensBefore(write, read) && write != read.readsFrom) {
                    relation[write, read.readsFrom] = true
                }
            }
        }
    }

    private fun computeReadModifyWriteChainsClosure(location: MemoryLocation) {
        relations[location]?.equivalenceClosure { event ->
            rmwChainsStorage[location, event]?.chain
        }
    }

}