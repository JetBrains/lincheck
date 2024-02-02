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
    val checkReleaseAcquireConsistency: Boolean = true,
    val approximateSequentialConsistency: Boolean = true,
    val computeCoherenceOrdering: Boolean = true,
) : ConsistencyChecker<AtomicThreadEvent, SequentialConsistencyWitness> {

    private val releaseAcquireChecker : ReleaseAcquireConsistencyChecker? =
        if (checkReleaseAcquireConsistency) ReleaseAcquireConsistencyChecker() else null

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
                    val executionIndex = verdict.witness.executionIndex
                    val rmwChainsStorage = verdict.witness.rmwChainsStorage
                    val writesBefore = verdict.witness.writesBefore
                    executionOrderApproximation = executionOrderApproximation union writesBefore
                    // TODO: combine SC approximation phase with coherence phase
                    if (computeCoherenceOrdering) {
                        return checkByCoherenceOrdering(execution, executionIndex, rmwChainsStorage, writesBefore)
                    }
                }
            }
        }
        // TODO: combine SC approximation phase with coherence phase (and remove this check)
        check(!computeCoherenceOrdering)
        if (approximateSequentialConsistency) {
            // TODO: embed the execution order approximation relation into the execution instance,
            //   so that this (and following stages) can be implemented as separate consistency check classes
            val executionIndex = MutableAtomicMemoryAccessEventIndex()
                .apply { index(execution) }
            val scApprox = SequentialConsistencyOrder(execution, executionIndex).apply {
                initialize()
                refine(executionOrderApproximation)
                compute()
            }
            if (scApprox.inconsistent) {
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
        CoherenceOrder.compute(execution, executionIndex, rmwChainsStorage, wbRelation).forEach { coherence ->
            val extendedCoherence = ExtendedCoherenceOrder(execution, executionIndex, rmwChainsStorage, coherence)
                .apply { compute() }
            val executionOrder = ExecutionOrder(execution, executionIndex, extendedCoherence).run {
                initialize()
                compute()
                executionOrder
            }
            if (executionOrder != null) {
                val replayer = SequentialConsistencyReplayer(1 + execution.maxThreadID)
                check(replayer.replay(executionOrder) != null)
                return SequentialConsistencyWitness.create(executionOrder)
            }
        }
        return SequentialConsistencyCoherenceViolation()
    }

}

class SequentialConsistencyCoherenceViolation : SequentialConsistencyViolation() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Sequential consistency coherence violation detected"
    }
}

class IncrementalSequentialConsistencyChecker(
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

class CoherenceOrder {

    private val coherenceMap = mutableMapOf<MemoryLocation, CoherenceList>()

    operator fun get(location: MemoryLocation): CoherenceList =
        coherenceMap[location] ?: emptyList()

    companion object {
        fun compute(
            execution: Execution<AtomicThreadEvent>,
            executionIndex: AtomicMemoryAccessEventIndex,
            rmwChainsStorage: ReadModifyWriteChainsStorage,
            relation: Relation<AtomicThreadEvent>
        ): Sequence<CoherenceOrder> {
            val coherenceOrderings = executionIndex.locations.mapNotNull { location ->
                val writes = executionIndex.getWrites(location)
                    // TODO: change the `takeIf` check to WW-racy check
                    .takeIf { it.size > 1 }
                    ?: return@mapNotNull null
                val enumerator = executionIndex.enumerator(AtomicMemoryAccessCategory.Write, location)!!
                topologicalSortings(relation.toGraph(writes, enumerator)).filter {
                    rmwChainsStorage.respectful(it)
                }
            }
            return coherenceOrderings.cartesianProduct().map { orderings ->
                val coherence = CoherenceOrder()
                for (ordering in orderings) {
                    val location = ordering
                        .first { it.label is WriteAccessLabel }
                        .let { (it.label as WriteAccessLabel).location }
                    coherence.coherenceMap[location] = ordering
                }
                return@map coherence
            }
        }
    }
}

class ExtendedCoherenceOrder(
    val execution: Execution<AtomicThreadEvent>,
    val executionIndex: AtomicMemoryAccessEventIndex,
    val rmwChainsStorage: ReadModifyWriteChainsStorage,
    val coherence: CoherenceOrder,
): Relation<AtomicThreadEvent> {

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

    fun compute() {
        initialize()
        addCoherenceEdges()
        addReadsFromEdges()
        addReadsBeforeEdges()
        addCoherenceReadFromEdges()
        addReadsBeforeReadsFromEdges()
    }

    private fun initialize() {
        for (location in executionIndex.locations) {
            val events = mutableListOf<AtomicThreadEvent>().apply {
                addAll(executionIndex.getWrites(location))
                addAll(executionIndex.getReadResponses(location))
            }
            relations[location] = RelationMatrix(events, buildEnumerator(events)) { x, y ->
                false
            }
        }
    }

    private fun addCoherenceEdges() {
        for (location in executionIndex.locations) {
            val relation = relations[location]!!
            relation.addTotalOrdering(coherence[location])
        }
    }

    private fun addReadsFromEdges() {
        for (location in executionIndex.locations) {
            addReadsFromEdges(location)
        }
    }

    private fun addReadsFromEdges(location: MemoryLocation) {
        val relation = relations[location]!!
        for (read in executionIndex.getReadResponses(location)) {
            relation[read.readsFrom, read] = true
        }
    }

    private fun addReadsBeforeEdges() {
        for (location in executionIndex.locations) {
            addReadsBeforeEdges(location)
        }
    }

    private fun addReadsBeforeEdges(location: MemoryLocation) {
        val relation = relations[location]!!
        for (read in executionIndex.getReadResponses(location)) {
            for (write in executionIndex.getWrites(location)) {
                if (relation(read.readsFrom, write)) {
                    relation[read, write] = true
                }
            }
        }
    }

    private fun addCoherenceReadFromEdges() {
        for (location in executionIndex.locations) {
            addCoherenceReadFromEdges(location)
        }
    }

    private fun addCoherenceReadFromEdges(location: MemoryLocation) {
        val relation = relations[location]!!
        for (read in executionIndex.getReadResponses(location)) {
            for (write in executionIndex.getWrites(location)) {
                if (relation(write, read.readsFrom)) {
                    relation[write, read] = true
                }
            }
        }
    }

    private fun addReadsBeforeReadsFromEdges() {
        for (location in executionIndex.locations) {
            addReadsBeforeReadsFromEdges(location)
        }
    }

    private fun addReadsBeforeReadsFromEdges(location: MemoryLocation) {
        val relation = relations[location]!!
        for (read1 in executionIndex.getReadResponses(location)) {
            for (read2 in executionIndex.getReadResponses(location)) {
                if (relation(read1, read2.readsFrom)) {
                    relation[read1, read2] = true
                }
            }
        }
    }
}

class SequentialConsistencyOrder(
    val execution: Execution<AtomicThreadEvent>,
    val executionIndex: AtomicMemoryAccessEventIndex,
) : Relation<AtomicThreadEvent> {

    private lateinit var relation: RelationMatrix<AtomicThreadEvent>

    private var initialized = false
    private var computed = false

    var inconsistent = false
        private set

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean =
        relation(x, y)

    fun initialize() {
        // TODO: optimize -- build the relation only for write and read-response events
        relation = RelationMatrix(execution, execution.buildEnumerator())
        initialized = true
        computed = false
    }

    fun refine(relation: Relation<AtomicThreadEvent>) {
        this.relation.add(relation)
        computed = false
    }

    fun compute() {
        var changed = !computed
        while (changed && !inconsistent) {
            changed = coherenceClosure() && relation.transitiveClosure()
            if (!relation.isIrreflexive()) {
                inconsistent = true
            }
        }
        computed = true
    }

    fun reset() {
        initialized = false
        computed = false
    }

    private fun coherenceClosure(): Boolean {
        var changed = false
        for (location in executionIndex.locations) {
            changed = changed || coherenceClosure(location)
        }
        return changed
    }

    private fun coherenceClosure(location: MemoryLocation): Boolean {
        var changed = false
        for (read in executionIndex.getReadResponses(location)) {
            for (write in executionIndex.getWrites(location)) {
                val readFrom = read.readsFrom
                if (write != readFrom && relation(write, read) && !relation(write, readFrom)) {
                    relation[write, readFrom] = true
                    changed = true
                }
                if (read != write && relation(readFrom, write) && !relation(read, write)) {
                    relation[read, write] = true
                    changed = true
                }
            }
        }
        return changed
    }

}

class ExecutionOrder(
    val execution: Execution<AtomicThreadEvent>,
    val executionIndex: AtomicMemoryAccessEventIndex,
    val extendedCoherence: ExtendedCoherenceOrder,
) {

    private lateinit var relation: RelationMatrix<AtomicThreadEvent>

    var executionOrder: List<AtomicThreadEvent>? = null
        private set

    var inconsistent = false
        private set

    fun initialize() {
        // TODO: optimize -- when adding extended-coherence edges iterate only through respective events
        relation = RelationMatrix(execution, execution.buildEnumerator(), causalityOrder.lessThan union extendedCoherence)
    }

    fun compute() {
        // TODO: remove this ad-hoc
        addRequestResponseEdges()
        executionOrder = topologicalSorting(relation.asGraph())
    }

    private fun addRequestResponseEdges() {
        for (response in execution) {
            if (!response.label.isResponse)
                continue
            // put notify event after wait-request
            if (response.label is WaitLabel) {
                relation[response.request!!, response.notifiedBy] = true
                continue
            }
            // otherwise, put request events after dependencies
            for (dependency in response.dependencies) {
                check(dependency is AtomicThreadEvent)
                relation[dependency, response.request!!] = true
            }
        }
    }

}