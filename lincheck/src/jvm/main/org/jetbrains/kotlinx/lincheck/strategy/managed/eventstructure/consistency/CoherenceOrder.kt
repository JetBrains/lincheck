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
import org.jetbrains.lincheck.util.*
import org.jetbrains.lincheck.util.collections.*

typealias CoherenceList = List<AtomicThreadEvent>

/**
 * Coherence order: a total ordering on same-location writes,
 * defining the order in which writes to the same memory location must be performed.
 *
 * The coherence order computation procedure searches for a coherence order
 * compatible with atomic updates and the memory model's causal order.
 *
 * Under release-acquire, any linearization of the [initialCoherenceApproximation] keeping read-modify-write chains contiguous
 * is a valid coherence order, so the first generated candidate is taken.
 *
 * Once computed, the coherence order is extended incrementally with the newly added writes:
 * since a new write is maximal in happens-before, it is put at the end of the coherence order.
 * The write-part of a read-modify-write is thus put right after the write it reads-from,
 * provided that this write is coherence-latest.
 * The consumers of the coherence order might check that its extension with the newly added events
 * is consistent (see [isConsistentExtension]), and invalidate it otherwise.
 *
 * @param execution the execution whose writes are ordered;
 *   under sequential consistency, each candidate coherence order is checked by building an [ExecutionOrder] over it,
 *   and the execution's extended coherence and execution orders are updated with the ones of the found order.
 * @param memoryAccessEventIndex the index supplying the writes to each memory location.
 * @param rmwChainsStorage the read-modify-write chains every candidate coherence order must respect.
 * @param initialCoherenceApproximation the initial approximation of the coherence order:
 *   a partial order on same-location writes, of which every candidate coherence order is a linearization.
 *   Also answers queries for locations without a computed coherence order (e.g. first accessed after computing it).
 *   Its own cached state must be computed by the caller before [compute].
 * @param coherenceCausalOrder the memory model's specific causal order,
 *   combined with a candidate coherence order to derive the extended coherence
 *   and the execution order (sequential-consistency order);
 *   usually either the happens-before or happens-before-per-location order.
 * @param memoryModel the memory model whose consistency the found coherence order witnesses.
 */
class CoherenceOrder(
    val execution: MutableExtendedExecution,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val rmwChainsStorage: ReadModifyWriteOrder,
    val initialCoherenceApproximation: Relation<AtomicThreadEvent>,
    val coherenceCausalOrder: Relation<AtomicThreadEvent>,
    val memoryModel: MemoryModel,
) : CachedRelation<AtomicThreadEvent>, ExecutionTracker<AtomicThreadEvent, Execution<AtomicThreadEvent>> {

    var computed = false
        private set

    var consistent: Boolean = true
        private set

    private class Entry(coherence: CoherenceList) {
        private val _coherence = coherence.toMutableList()
        val coherence: CoherenceList get() = _coherence

        private val positions = coherence.withIndex().associateTo(mutableMapOf()) { (i, write) -> write to i }

        fun position(write: AtomicThreadEvent): Int =
            positions[write]!!

        fun append(write: AtomicThreadEvent) {
            positions[write] = _coherence.size
            _coherence.add(write)
        }
    }

    private val map = mutableMapOf<MemoryLocation, Entry>()

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean = relates(map, x, y)

    private fun relates(order: Map<MemoryLocation, Entry>, x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        val location = getLocationForSameLocationWriteAccesses(x, y)
            ?: return false
        val entry = order[location]
            ?: return initialCoherenceApproximation(x, y)
        return entry.position(x) < entry.position(y)
    }

    operator fun get(location: MemoryLocation): CoherenceList =
        map[location]?.coherence ?: emptyList()

    override fun onAdd(event: AtomicThreadEvent) {
        if (!computed || !consistent) return
        val label = event.label as? WriteAccessLabel ?: return
        addWrite(event, label.location)
    }

    override fun onReset(execution: Execution<AtomicThreadEvent>) {
        invalidate()
    }

    override fun invalidate() {
        computed = false
        map.clear()
        consistent = true
    }

    override fun compute() {
        if (computed) return
        rmwChainsStorage.compute()
        for (coherence in generate(memoryAccessEventIndex, rmwChainsStorage, initialCoherenceApproximation)) {
            if (memoryModel == MemoryModel.SequentialConsistency) {
                // under the sequential consistency model, check that generated coherence order
                // satisfies sequential consistency constraints:
                // if so, save it and exit the loop, if not, try the next one
                if (!checkSequentialConsistency(coherence)) continue
            } else if (memoryModel == MemoryModel.RelaxedJava) {
                // under the JMM, check that generated coherence order satisfies the volatile PSC constraint:
                if (!checkVolatileAccessesOrder(coherence)) continue
            }
            this.map += coherence
            computed = true
            return
        }
        // if we reached this point, then none of the generated coherence orderings is consistent
        consistent = false
        computed = true
    }

    /**
     * Checks that the candidate [coherence] order induces an acyclic execution order;
     * if so, saves the induced extended coherence and execution orders.
     */
    private fun checkSequentialConsistency(coherence: Map<MemoryLocation, Entry>): Boolean {
        val order = Relation<AtomicThreadEvent> { x, y -> relates(coherence, x, y) }
        val extendedCoherence = ExtendedCoherenceOrder(memoryAccessEventIndex,
            writesOrder = order union coherenceCausalOrder
        )
            .apply { compute() }

        val executionOrder = ExecutionOrder(execution,
            approximation = extendedCoherence union coherenceCausalOrder,
        )
            .apply { compute() }
        if (!executionOrder.consistent)
            return false
        this.execution.extendedCoherence.copyFrom(extendedCoherence)
        this.execution.executionOrder.copyFrom(executionOrder)
        return true
    }

    /**
     * Checks the volatile PSC order for a candidate JMM coherence order.
     */
    private fun checkVolatileAccessesOrder(coherence: Map<MemoryLocation, Entry>): Boolean {
        val volatileEvents = execution.filter { it.isVolatileAccess() }
        if (volatileEvents.isEmpty()) return true
        val enumerator = buildEnumerator(volatileEvents)
        val scRelation = SequentialConsistencyMixedAccessRelation(
            execution,
            coherenceOrder = { x, y -> relates(coherence, x, y) },
        )
        // The relation is not transitively closed, but this is not required:
        // a topological sorting of the relation also respects its transitive closure,
        // and it does not exist (`topologicalSorting` returns null) iff the relation has a cycle.
        return topologicalSorting(scRelation.toGraph(volatileEvents, enumerator)) != null
    }

    /**
     * Retrieves the coherence-latest write event to the specified memory location.
     *
     * @param location the memory location for which the latest write event is to be retrieved
     * @return the latest write event to the specified location, or null if no such event exists
     */
    fun getLatestWrite(location: MemoryLocation): AtomicThreadEvent? =
        map[location]?.coherence?.last() ?: memoryAccessEventIndex.getLastWrite(location)

    /**
     * Checks whether the extension of the coherence order with the newly added [event] is consistent.
     *
     * For a read response, checks that it reads-from the coherence-latest write to its location.
     * For the write-part of a read-modify-write (RMW), already appended to the coherence order,
     * checks that the read-part of RMW reads-from the write preceding it.
     * The read-part itself might not have been checked against this coherence order,
     * since the order might have been computed after the read-part was added.
     * For other events, returns true.
     *
     * Requires the coherence order to be computed and consistent,
     * and the [event] to be the last added one.
     */
    fun isConsistentExtension(event: AtomicThreadEvent): Boolean {
        check(computed && consistent)
        val label = event.label
        return when {
            label is ReadAccessLabel && label.isResponse ->
                event.readsFrom == getLatestWrite(label.location)
            label is WriteAccessLabel && label.isExclusive -> {
                val coherence = this[label.location]
                event.exclusiveReadPart.readsFrom == coherence[coherence.lastIndex - 1]
            }
            else -> true
        }
    }

    private fun addWrite(write: AtomicThreadEvent, location: MemoryLocation) {
        val entry = map.getOrPut(location) {
            // the location was first accessed after the coherence order computation,
            // so the only preceding write is its initial one
            Entry(memoryAccessEventIndex.getWrites(location).filter { it != write })
        }
        entry.append(write)
    }

    companion object {

        private fun generate(
            memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
            rmwChainsStorage: ReadModifyWriteOrder,
            writesOrder: Relation<AtomicThreadEvent>,
        ): Sequence<Map<MemoryLocation, Entry>> {
            val coherenceOrderings = memoryAccessEventIndex.locations.map { location ->
                val writes = memoryAccessEventIndex.getWrites(location)
                val orderings = if (writes.size == 1) sequenceOf(writes) else {
                    val enumerator = memoryAccessEventIndex.enumerator(AtomicMemoryAccessCategory.Write, location)!!
                    topologicalSortings(writesOrder.toGraph(writes, enumerator)).filter {
                        rmwChainsStorage.respectful(it)
                    }
                }
                orderings.map { location to it }
            }
            if (coherenceOrderings.isEmpty()) {
                return sequenceOf(emptyMap())
            }
            return coherenceOrderings.cartesianProduct().map { coherenceList ->
                coherenceList.associate { (location, coherence) -> location to Entry(coherence) }
            }
        }

    }
}

/**
 * Extended coherence order: a partial order on the same-location writes and reads.
 *
 * Order almost all memory access events to the same location,
 * except concurrent read-read pairs reading from the same write event.
 */
class ExtendedCoherenceOrder(
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val writesOrder: Relation<AtomicThreadEvent>,
) : CachedRelation<AtomicThreadEvent>, ExecutionTracker<AtomicThreadEvent, Execution<AtomicThreadEvent>> {

    private var computed = false

    override fun onAdd(event: AtomicThreadEvent) {
        invalidate()
    }

    override fun onReset(execution: Execution<AtomicThreadEvent>) {
        invalidate()
    }

    internal fun copyFrom(other: ExtendedCoherenceOrder) {
        relations.clear()
        relations.putAll(other.relations)
        computed = true
    }

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

    private fun initialize() {
        for (location in memoryAccessEventIndex.locations) {
            val events = mutableListOf<AtomicThreadEvent>().apply {
                addAll(memoryAccessEventIndex.getWrites(location))
                addAll(memoryAccessEventIndex.getReadResponses(location))
            }
            relations[location] = RelationMatrix(events, buildEnumerator(events))
        }
    }

    override fun compute() {
        if (computed) return
        initialize()
        addCoherenceEdges()
        addReadsFromEdges()
        addReadsBeforeEdges()
        addCoherenceReadFromEdges()
        addReadsBeforeReadsFromEdges()
        computed = true
    }

    override fun invalidate() {
        computed = false
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
