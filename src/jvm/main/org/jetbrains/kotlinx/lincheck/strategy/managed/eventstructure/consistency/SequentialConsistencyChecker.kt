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
import org.jetbrains.kotlinx.lincheck.utils.*
import kotlin.collections.*


typealias SequentialConsistencyVerdict = ConsistencyVerdict<SequentialConsistencyWitness>

class SequentialConsistencyWitness(
    val executionOrder: List<AtomicThreadEvent>
) {
    companion object {
        fun create(executionOrder: List<AtomicThreadEvent>): ConsistencyWitness<SequentialConsistencyWitness> {
            return ConsistencyWitness(SequentialConsistencyWitness(executionOrder))
        }
    }
}

abstract class SequentialConsistencyViolation : Inconsistency()

class SequentialConsistencyChecker(
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val checkReleaseAcquireConsistency: Boolean = true,
    val approximateSequentialConsistency: Boolean = true,
    val computeCoherenceOrdering: Boolean = true,
) : ConsistencyChecker<AtomicThreadEvent, SequentialConsistencyWitness> {

    private val releaseAcquireChecker : ReleaseAcquireConsistencyChecker? =
        if (checkReleaseAcquireConsistency) ReleaseAcquireConsistencyChecker(memoryAccessEventIndex) else null

    override fun check(execution: Execution<AtomicThreadEvent>): SequentialConsistencyVerdict {
        // we will gradually approximate the total sequential execution order of events
        // by a partial order, starting with the partial causality order
        var executionOrderApproximation : Relation<AtomicThreadEvent> = causalityOrder.lessThan
        // first try to check release/acquire consistency (it is cheaper) ---
        // release/acquire inconsistency will also imply violation of sequential consistency,
        if (releaseAcquireChecker != null) {
            when (val verdict = releaseAcquireChecker.check(execution)) {
                is ReleaseAcquireInconsistency -> return verdict
                is ConsistencyWitness -> {
                    // if execution is release/acquire consistent,
                    // the writes-before relation can be used
                    // to refine the execution ordering approximation
                    val rmwChainsStorage = verdict.witness.rmwChainsStorage
                    val writesBefore = verdict.witness.writesBefore
                    executionOrderApproximation = executionOrderApproximation union writesBefore
                    // TODO: combine SC approximation phase with coherence phase
                    if (computeCoherenceOrdering) {
                        return checkByCoherenceOrdering(execution, memoryAccessEventIndex, rmwChainsStorage, writesBefore)
                    }
                }
            }
        }
        // TODO: combine SC approximation phase with coherence phase (and remove this check)
        check(!computeCoherenceOrdering)
        if (approximateSequentialConsistency) {
            // TODO: embed the execution order approximation relation into the execution instance,
            //   so that this (and following stages) can be implemented as separate consistency check classes
            val executionIndex = MutableAtomicMemoryAccessEventIndex(execution)
                .apply { index(execution) }
            val scApprox = SequentialConsistencyOrder(execution, executionIndex, executionOrderApproximation).apply {
                initialize()
                compute()
            }
            if (!scApprox.isIrreflexive()) {
                return SequentialConsistencyApproximationInconsistency()
            }
            executionOrderApproximation = scApprox
        }
        // get dependency covering to guide the search
        val covering = execution.buildExternalCovering(executionOrderApproximation)
        // aggregate atomic events before replaying
        val (aggregated, remapping) = execution.aggregate(ThreadAggregationAlgebra.aggregator())
        // check consistency by trying to replay execution using sequentially consistent abstract machine
        return checkByReplaying(aggregated, covering.aggregate(remapping))
    }

    private fun checkByCoherenceOrdering(
        execution: Execution<AtomicThreadEvent>,
        executionIndex: AtomicMemoryAccessEventIndex,
        rmwChainsStorage: ReadModifyWriteChainsStorage,
        wbRelation: WritesBeforeRelation,
    ): ConsistencyVerdict<SequentialConsistencyWitness> {
        val writesOrder = causalityOrder.lessThan union wbRelation
        val executionOrderComputable = computable {
            ExecutionOrder(execution, executionIndex, Relation.empty())
        }
        val coherence = CoherenceOrder(execution, executionIndex, rmwChainsStorage, writesOrder,
                executionOrder = executionOrderComputable
            )
            .apply { initialize(); compute() }
        if (!coherence.consistent)
            return SequentialConsistencyCoherenceViolation()
        val executionOrder = executionOrderComputable.value.ensure { it.consistent }
        SequentialConsistencyReplayer(1 + execution.maxThreadID).ensure {
            it.replay(executionOrder) != null
        }
        return SequentialConsistencyWitness.create(executionOrder)
    }

}

class SequentialConsistencyCoherenceViolation : SequentialConsistencyViolation() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Sequential consistency coherence violation detected"
    }
}

class IncrementalSequentialConsistencyChecker(
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    checkReleaseAcquireConsistency: Boolean = true,
    approximateSequentialConsistency: Boolean = true
) : IncrementalConsistencyChecker<AtomicThreadEvent, SequentialConsistencyWitness> {

    private var execution = executionOf<AtomicThreadEvent>()

    private val _executionOrder = mutableListOf<AtomicThreadEvent>()

    val executionOrder: List<AtomicThreadEvent>
        get() = _executionOrder

    private var executionOrderEnabled = true

    private val lockConsistencyChecker = LockConsistencyChecker()

    private val sequentialConsistencyChecker = SequentialConsistencyChecker(
        memoryAccessEventIndex,
        checkReleaseAcquireConsistency,
        approximateSequentialConsistency,
    )

    override fun check(): SequentialConsistencyVerdict {
        // TODO: expensive check???
        // check(execution.enumerationOrderSortedList() == executionOrder.sorted())

        // check locks consistency
        when (val verdict = lockConsistencyChecker.check(execution)) {
            is LockConsistencyViolation -> return verdict
        }
        // first try to replay according to execution order
        if (checkByExecutionOrderReplaying()) {
            return SequentialConsistencyWitness.create(executionOrder)
        }
        val verdict = sequentialConsistencyChecker.check(execution)
        if (verdict is Inconsistency)
            return verdict
        check(verdict is ConsistencyWitness)
        // TODO: invent a nicer way to handle blocked dangling requests
        val (events, blockedRequests) = verdict.witness.executionOrder.partition {
            !execution.isBlockedDanglingRequest(it)
        }
        _executionOrder.apply {
            clear()
            addAll(events)
            addAll(blockedRequests)
        }
        executionOrderEnabled = true
        return SequentialConsistencyWitness.create(executionOrder)
    }

    override fun check(event: AtomicThreadEvent): SequentialConsistencyVerdict {
        if (!executionOrderEnabled)
            return ConsistencyUnknown
        if (!event.extendsExecutionOrder()) {
            _executionOrder.clear()
            executionOrderEnabled = false
            return ConsistencyUnknown
        }
        _executionOrder.add(event)
        return SequentialConsistencyWitness.create(executionOrder)
    }

    override fun reset(execution: Execution<AtomicThreadEvent>) {
        this.execution = execution
        _executionOrder.clear()
        executionOrderEnabled = true
        for (event in execution.enumerationOrderSorted()) {
            check(event)
        }
    }

    private fun checkByExecutionOrderReplaying(): Boolean {
        if (!executionOrderEnabled)
            return false
        val replayer = SequentialConsistencyReplayer(1 + execution.maxThreadID)
        return (replayer.replay(executionOrder) != null)
    }

    // TODO: can we get rid of this (by ensuring we always replay atomic list of events atomically)?
    private fun AtomicThreadEvent.extendsExecutionOrder(): Boolean {
        // TODO: this check should be generalized ---
        //   it should be derivable from the aggregation algebra
        if (label is ReadAccessLabel && label.isResponse) {
            val last = executionOrder.lastOrNull()
                ?: return false
            return isValidResponse(last)
        }
        if (label is WriteAccessLabel && (label as WriteAccessLabel).isExclusive) {
            val last = executionOrder.lastOrNull()
                ?: return false
            return isWritePartOfAtomicUpdate(last)
        }
        return true
    }
}


class SequentialConsistencyApproximationInconsistency : SequentialConsistencyViolation() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Approximate sequential inconsistency detected"
    }
}

typealias CoherenceList = List<AtomicThreadEvent>
typealias MutableCoherenceList = MutableList<AtomicThreadEvent>

class CoherenceOrder(
    val execution: Execution<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val rmwChainsStorage: ReadModifyWriteChainsStorage,
    val writesOrder: Relation<AtomicThreadEvent>,
    var extendedCoherenceOrder: ComputableDelegate<ExtendedCoherenceOrder>? = null,
    var executionOrder: ComputableDelegate<ExecutionOrder>? = null,
) : Relation<AtomicThreadEvent>, Computable {

    var consistent: Boolean = true
        private set

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
                    writesOrder = causalityOrder.lessThan union coherence
                )
                .apply { initialize(); compute() }
            val executionOrder = ExecutionOrder(execution, memoryAccessEventIndex,
                    approximation = causalityOrder.lessThan union extendedCoherence
                )
                .apply { initialize(); compute() }
            if (!executionOrder.consistent)
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
            rmwChainsStorage: ReadModifyWriteChainsStorage,
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
        if (!(inField(x) && inField(y)))
            return false
        return relations[location]?.get(x, y) ?: false
    }

    private fun inField(x: AtomicThreadEvent): Boolean {
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

class SequentialConsistencyOrder(
    val execution: Execution<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val memoryAccessOrder: Relation<AtomicThreadEvent>,
) : Relation<AtomicThreadEvent>, Computable {

    private var relation: RelationMatrix<AtomicThreadEvent>? = null

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean =
        relation?.invoke(x, y) ?: false

    // TODO: make cached delegate?
    private var irreflexive = true

    fun isIrreflexive(): Boolean {
        return irreflexive
    }

    override fun initialize() {
        // TODO: optimize -- build the relation only for write and read-response events
        relation = RelationMatrix(execution, execution.buildEnumerator())
    }

    override fun compute() {
        val relation = this.relation!!
        relation.add(memoryAccessOrder)
        relation.fixpoint {
            // TODO: maybe we can remove this check without affecting performance?
            if (!isIrreflexive()) {
                irreflexive = false
                return@fixpoint
            }
            coherenceClosure()
            transitiveClosure()
        }
    }

    override fun invalidate() {
        irreflexive = true
    }

    override fun reset() {
        invalidate()
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

class ExecutionOrder private constructor(
    val execution: Execution<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val approximation: Relation<AtomicThreadEvent>,
    private val ordering: MutableList<AtomicThreadEvent>,
) : Relation<AtomicThreadEvent>, List<AtomicThreadEvent> by ordering, Computable {

    var consistent = true
        private set

    private val additionalOrdering = Relation<AtomicThreadEvent> { x, y ->
        when {
            // put wait-request before notify event
            x.label.isRequest && x.label is WaitLabel &&
                y == execution.getResponse(x)?.notifiedBy -> true

            // put dependencies of response before corresponding request
            y.label.isRequest && y.label !is LockLabel && y.label !is ActorLabel &&
                x in execution.getResponse(y)?.dependencies.orEmpty()-> true

            else -> false
        }
    }

    constructor(
        execution: Execution<AtomicThreadEvent>,
        memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
        approximation: Relation<AtomicThreadEvent>,
    ) : this(execution, memoryAccessEventIndex, approximation, mutableListOf())

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        TODO("Not yet implemented")
    }

    override fun compute() {
        check(ordering.isEmpty())
        // TODO: although we have to add these additional ordering constraints here,
        //  it is not a completely sound way to enforce additional atomicity constraints;
        //  instead we can ensure additional atomicity constraints
        //  by reordering some events after topological sorting
        val relation = approximation union additionalOrdering
        val graph = execution.buildGraph(relation)
        val ordering = topologicalSorting(graph)
        if (ordering == null) {
            consistent = false
            return
        }
        this.ordering.addAll(ordering)
    }

    override fun invalidate() {
        consistent = true
    }

    override fun reset() {
        ordering.clear()
        invalidate()
    }

}