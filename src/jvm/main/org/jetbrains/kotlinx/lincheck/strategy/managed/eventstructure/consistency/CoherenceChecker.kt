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
import org.jetbrains.kotlinx.lincheck.util.*

typealias CoherenceList = List<AtomicThreadEvent>

class CoherenceChecker : ConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution> {

    override fun check(execution: MutableExtendedExecution): Inconsistency? {
        execution.coherenceOrderComputable.apply {
            initialize()
            compute()
        }
        val coherenceOrder = execution.coherenceOrderComputable.value
        return if (!coherenceOrder.isConsistent())
            CoherenceViolation()
        else null
    }

}

class CoherenceOrder(
    val execution: Execution<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val rmwChainsStorage: ReadModifyWriteOrder,
    val writesOrder: Relation<AtomicThreadEvent>,
    var extendedCoherenceOrder: ComputableNode<ExtendedCoherenceOrder>? = null,
    var executionOrder: ComputableNode<ExecutionOrder>? = null,
) : Relation<AtomicThreadEvent>, Computable {

    private var consistent: Boolean = true

    private data class Entry(
        val coherence: CoherenceList,
        val positions: List<Int>,
        val enumerator: Enumerator<AtomicThreadEvent>,
    )

    private val map = mutableMapOf<MemoryLocation, Entry>()

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        val location = getLocationForSameLocationWriteAccesses(x, y)
            ?: return false
        val (_, positions, enumerator) = map[location]
            ?: return writesOrder(x, y)
        return positions[enumerator[x]] < positions[enumerator[y]]
    }

    operator fun get(location: MemoryLocation): CoherenceList =
        map[location]?.coherence ?: emptyList()

    fun isConsistent(): Boolean =
        consistent

    override fun invalidate() {
        reset()
    }

    override fun reset() {
        map.clear()
        consistent = true
    }

    override fun compute() {
        check(map.isEmpty())
        generate(execution, memoryAccessEventIndex, rmwChainsStorage, writesOrder).forEach { coherence ->
            val extendedCoherence = ExtendedCoherenceOrder(execution, memoryAccessEventIndex,
                writesOrder = causalityOrder union coherence
            )
                .apply { initialize(); compute() }
            val executionOrder = ExecutionOrder(execution, memoryAccessEventIndex,
                approximation = causalityOrder union extendedCoherence
            )
                .apply { initialize(); compute() }
            if (!executionOrder.isConsistent())
                return@forEach
            this.map += coherence.map
            this.extendedCoherenceOrder?.setComputed(extendedCoherence)
            this.executionOrder?.setComputed(executionOrder)
            return
        }
        // if we reached this point, then none of the generated coherence orderings is consistent
        consistent = false
    }

    companion object {

        private fun generate(
            execution: Execution<AtomicThreadEvent>,
            memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
            rmwChainsStorage: ReadModifyWriteOrder,
            writesOrder: Relation<AtomicThreadEvent>
        ): Sequence<CoherenceOrder> {
            val coherenceOrderings = memoryAccessEventIndex.locations.mapNotNull { location ->
                if (memoryAccessEventIndex.isWriteWriteRaceFree(location))
                    return@mapNotNull null
                val writes = memoryAccessEventIndex.getWrites(location)
                    .takeIf { it.size > 1 } ?: return@mapNotNull null
                val enumerator = memoryAccessEventIndex.enumerator(AtomicMemoryAccessCategory.Write, location)!!
                topologicalSortings(writesOrder.toGraph(writes, enumerator)).filter {
                    rmwChainsStorage.respectful(it)
                }
            }
            if (coherenceOrderings.isEmpty()) {
                return sequenceOf(
                    CoherenceOrder(execution, memoryAccessEventIndex, rmwChainsStorage, writesOrder)
                )
            }
            return coherenceOrderings.cartesianProduct().map { coherenceList ->
                val coherenceOrder = CoherenceOrder(execution, memoryAccessEventIndex, rmwChainsStorage, writesOrder)
                for (coherence in coherenceList) {
                    val location = coherence.getLocationForSameLocationWriteAccesses()!!
                    val enumerator = memoryAccessEventIndex.enumerator(AtomicMemoryAccessCategory.Write, location)!!
                    val positions = MutableList(coherence.size) { 0 }
                    coherence.forEachIndexed { i, write ->
                        positions[enumerator[write]] = i
                    }
                    coherenceOrder.map[location] = Entry(coherence, positions, enumerator)
                }
                return@map coherenceOrder
            }
        }

    }
}

class ExtendedCoherenceOrder(
    val execution: Execution<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val writesOrder: Relation<AtomicThreadEvent>,
): Relation<AtomicThreadEvent>, Computable {

    private val relations: MutableMap<MemoryLocation, RelationMatrix<AtomicThreadEvent>> = mutableMapOf()

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        val location = getLocationForSameLocationAccesses(x, y)
            ?: return false
        if (!(isWriteOrReadResponse(x) && isWriteOrReadResponse(y)))
            return false
        return relations[location]?.get(x, y) ?: false
    }

    private fun isWriteOrReadResponse(x: AtomicThreadEvent): Boolean {
        return (x.label.isWriteAccess() || x.label is ReadAccessLabel && x.label.isResponse)
    }

    fun isIrreflexive(): Boolean =
        relations.all { (_, relation) -> relation.isIrreflexive() }

    override fun initialize() {
        for (location in memoryAccessEventIndex.locations) {
            val events = mutableListOf<AtomicThreadEvent>().apply {
                addAll(memoryAccessEventIndex.getWrites(location))
                addAll(memoryAccessEventIndex.getReadResponses(location))
            }
            relations[location] = RelationMatrix(events, buildEnumerator(events))
        }
    }

    override fun compute() {
        addCoherenceEdges()
        addReadsFromEdges()
        addReadsBeforeEdges()
        addCoherenceReadFromEdges()
        addReadsBeforeReadsFromEdges()
    }

    override fun reset() {
        relations.clear()
    }

    private fun addCoherenceEdges() {
        for (location in memoryAccessEventIndex.locations) {
            addCoherenceEdges(location)
        }
    }

    private fun addCoherenceEdges(location: MemoryLocation) {
        val relation = relations[location]!!
        for (write1 in memoryAccessEventIndex.getWrites(location)) {
            for (write2 in memoryAccessEventIndex.getWrites(location)) {
                if (write1 != write2 && writesOrder(write1, write2))
                    relation[write1, write2] = true
            }
        }
    }

    private fun addReadsFromEdges() {
        for (location in memoryAccessEventIndex.locations) {
            addReadsFromEdges(location)
        }
    }

    private fun addReadsFromEdges(location: MemoryLocation) {
        val relation = relations[location]!!
        for (read in memoryAccessEventIndex.getReadResponses(location)) {
            relation[read.readsFrom, read] = true
        }
    }

    private fun addReadsBeforeEdges() {
        for (location in memoryAccessEventIndex.locations) {
            addReadsBeforeEdges(location)
        }
    }

    private fun addReadsBeforeEdges(location: MemoryLocation) {
        val relation = relations[location]!!
        for (read in memoryAccessEventIndex.getReadResponses(location)) {
            for (write in memoryAccessEventIndex.getWrites(location)) {
                if (relation(read.readsFrom, write)) {
                    relation[read, write] = true
                }
            }
        }
    }

    private fun addCoherenceReadFromEdges() {
        for (location in memoryAccessEventIndex.locations) {
            addCoherenceReadFromEdges(location)
        }
    }

    private fun addCoherenceReadFromEdges(location: MemoryLocation) {
        val relation = relations[location]!!
        for (read in memoryAccessEventIndex.getReadResponses(location)) {
            for (write in memoryAccessEventIndex.getWrites(location)) {
                if (relation(write, read.readsFrom)) {
                    relation[write, read] = true
                }
            }
        }
    }

    private fun addReadsBeforeReadsFromEdges() {
        for (location in memoryAccessEventIndex.locations) {
            addReadsBeforeReadsFromEdges(location)
        }
    }

    private fun addReadsBeforeReadsFromEdges(location: MemoryLocation) {
        val relation = relations[location]!!
        for (read1 in memoryAccessEventIndex.getReadResponses(location)) {
            for (read2 in memoryAccessEventIndex.getReadResponses(location)) {
                if (relation(read1, read2.readsFrom)) {
                    relation[read1, read2] = true
                }
            }
        }
    }
}