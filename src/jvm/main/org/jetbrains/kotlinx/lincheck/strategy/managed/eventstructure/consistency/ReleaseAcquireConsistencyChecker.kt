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
import org.jetbrains.kotlinx.lincheck.utils.*

// TODO: what information should we display to help identify the cause of inconsistency:
//   a cycle in writes-before relation?
class ReleaseAcquireInconsistency() : Inconsistency() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Release/Acquire inconsistency detected"
    }
}

class ReleaseAcquireConsistencyWitness(
    val writesBefore: WritesBeforeRelation
)

class ReleaseAcquireConsistencyChecker : ConsistencyChecker<AtomicThreadEvent, ReleaseAcquireConsistencyWitness> {

    override fun check(execution: Execution<AtomicThreadEvent>): ConsistencyVerdict<ReleaseAcquireConsistencyWitness> {
        val writesBeforeRelation = WritesBeforeRelation(execution)
        return if (!writesBeforeRelation.isIrreflexive())
            ReleaseAcquireInconsistency()
        else
            ConsistencyWitness(ReleaseAcquireConsistencyWitness(writesBeforeRelation))
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

    fun initialize(initialApproximation: Relation<AtomicThreadEvent>) {
        if (isInitialized)
            return
        for (location in executionIndex.locations) {
            val writes = executionIndex.getWrites(location)
            val relation = RelationMatrix(writes, buildIndexer(writes))
            // add edges between the writes according to the initial approximation
            relation.add { x, y ->
                initialApproximation(x, y)
                // causalityOrder.lessThan(x, y)
            }
            // add edges to order rmw-chains accordingly
            for (chain in rmwChainsStorage[location].orEmpty()) {
                relation.addTotalOrdering(chain)
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
            var changed = false
            readLoop@ for (read in executionIndex.getReadResponses(location)) {
                val readFrom = read.readsFrom
                val readFromChain = rmwChainsStorage[readFrom]?.chain
                writeLoop@ for (write in executionIndex.getWrites(location)) {
                    val writeChain = rmwChainsStorage[write]?.chain
                    if (causalityOrder.lessThan(write, read)) {
                        relation.updateIrrefl(write, readFrom).also {
                            changed = changed || it
                        }
                        if ((writeChain != null || readFromChain != null) &&
                            (writeChain !== readFromChain)
                        ) {
                            relation.updateIrrefl(writeChain?.last() ?: write, readFromChain?.first() ?: readFrom)
                                .also {
                                    changed = changed || it
                                }
                        }
                    }
                }
            }
            if (changed) {
                relation.transitiveClosure()
            }
        }
        isComputed = true
    }

    fun reset() {
        relations.clear()
        isInitialized = false
        isComputed = false
    }

    private fun<T> RelationMatrix<T>.updateIrrefl(x: T, y: T): Boolean {
        return if ((x != y) && !this[x, y]) {
            this[x, y] = true
            true
        } else false
    }

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        // TODO: make this code pattern look nicer (it appears several times in codebase)
        var xloc = (x.label as? WriteAccessLabel)?.location
        var yloc = (y.label as? WriteAccessLabel)?.location
        if (xloc == null && yloc != null && x.label.isWriteAccessTo(yloc))
            xloc = yloc
        if (xloc != null && yloc == null && y.label.isWriteAccessTo(xloc))
            yloc = xloc
        return if (xloc != null && xloc == yloc) {
            relations[xloc]?.get(x, y) ?: false
        } else false
    }

    fun isIrreflexive(): Boolean =
        relations.all { (_, relation) -> relation.isIrreflexive() }

    fun generateCoherenceTotalOrderings(): Sequence<List<List<AtomicThreadEvent>>> {
        val coherenceOrderings = relations.mapNotNull { (_, relation) ->
            // TODO: remove this check!
            if (relation.nodes.size <= 1)
                return@mapNotNull null
            topologicalSortings(relation.asGraph()).filter {
                // filter-out coherence orderings violating atomicity
                respectsAtomicity(it)
            }
        }
        return coherenceOrderings.cartesianProduct()
    }

    private fun respectsAtomicity(coherence: List<AtomicThreadEvent>): Boolean {
        check(coherence.isNotEmpty())
        val location = coherence
            .first { it.label is WriteAccessLabel }
            .let { (it.label as WriteAccessLabel).location }
        // atomicity violation occurs when a write event is put in the middle of some rmw chain
        val chains = rmwChainsStorage[location]?.ensure { it.isNotEmpty() } ?:
            return true
        var i = 0
        var pos = 0
        while (pos + chains[i].size <= coherence.size) {
            // TODO: fix this IF (if chain is not a sublist of coherence exit immediately)
            if (coherence[pos] == chains[i].first() &&
                coherence.subList(pos, pos + chains[i].size) == chains[i]) {
                pos += chains[i].size
                if (++i == chains.size)
                    return true
            } else {
                pos++
            }
        }
        return false
    }
}