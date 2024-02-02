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
        val writesBeforeRelation = WritesBeforeRelation(execution, executionIndex, rmwChainsStorage)
            .apply {
                initialize(causalityOrder.lessThan)
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
) : Relation<AtomicThreadEvent> {

    private val relations: MutableMap<MemoryLocation, RelationMatrix<AtomicThreadEvent>> = mutableMapOf()

    // TODO: think more about relations API, in particular,
    //   - provision the fixpoint computations,
    //   - provision the interplay between different relations

    private var isInitialized = false
    private var isComputed = false

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        // TODO: make this code pattern look nicer (it appears several times in codebase)
        val location = getLocationForSameLocationWriteAccesses(x, y)
            ?: return false
        return relations[location]?.get(x, y) ?: false
    }

    fun initialize(initialApproximation: Relation<AtomicThreadEvent>) {
        if (isInitialized)
            return
        for (location in executionIndex.locations) {
            val writes = executionIndex.getWrites(location)
            val enumerator = executionIndex.enumerator(AtomicMemoryAccessCategory.Write, location)!!
            val relation = RelationMatrix(writes, enumerator)
            // add edges between the writes according to the initial approximation
            relation.add { x, y ->
                initialApproximation(x, y)
                // causalityOrder.lessThan(x, y)
            }
            // add edges to order rmw-chains accordingly
            for (chain in rmwChainsStorage[location].orEmpty()) {
                relation.order(chain)
            }
            // compute transitive closure
            relation.transitiveClosure()
            // set the relation
            relations[location] = relation
        }
        isInitialized = true
    }

    fun compute() {
        if (isComputed)
            return
        for ((location, relation) in relations) {
            val changed = relation.trackChanges {
                // TODO: refactor this, make the receiver object obvious
                compute(location)
            }
            if (changed) {
                relation.transitiveClosure()
            }
        }
        isComputed = true
    }

    private fun compute(location: MemoryLocation) {
        val relation = relations[location]!!
        for (read in executionIndex.getReadResponses(location)) {
            val readFrom = read.readsFrom
            val readFromChain = rmwChainsStorage[location, readFrom]?.chain
            for (write in executionIndex.getWrites(location)) {
                val writeChain = rmwChainsStorage[location, write]?.chain
                // TODO: change this check from `hb` to `rf^?;hb`
                if (causalityOrder.lessThan(write, read)) {
                    if (write != readFrom) {
                        relation[write, readFrom] = true
                    }
                    // TODO: add more generic equivalence-closure function
                    if ((writeChain != null || readFromChain != null) && (writeChain !== readFromChain)) {
                        relation.updateIrrefl(writeChain?.last() ?: write, readFromChain?.first() ?: readFrom)
                    }
                }
            }
        }
    }

    fun reset() {
        relations.clear()
        isInitialized = false
        isComputed = false
    }

    private fun<T> RelationMatrix<T>.updateIrrefl(x: T, y: T) {
        if ((x != y) && !this[x, y]) {
            this[x, y] = true
        }
    }

    fun isIrreflexive(): Boolean =
        relations.all { (_, relation) -> relation.isIrreflexive() }

}