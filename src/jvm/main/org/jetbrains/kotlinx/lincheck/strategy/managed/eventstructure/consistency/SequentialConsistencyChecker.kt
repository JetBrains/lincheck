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
        return checkTest(execution)

        // we will gradually approximate the total sequential execution order of events
        // by a partial order, starting with the partial causality order
        var executionOrderApproximation : Relation<AtomicThreadEvent> = causalityOrder
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
            val executionIndex = MutableAtomicMemoryAccessEventIndex()
                .apply { index(execution) }
            val scApprox = SequentialConsistencyOrder(execution, executionIndex, executionOrderApproximation).apply {
                initialize()
                compute()
            }
            if (!scApprox.isConsistent()) {
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

    private fun checkTest(execution: Execution<AtomicThreadEvent>): SequentialConsistencyVerdict {
        val extendedExecution = ExtendedExecutionImpl(ResettableExecution(execution.toFrontier().toMutableExecution()))
        extendedExecution.readModifyWriteOrderComputable.apply {
            initialize(); compute()
        }
        if (!extendedExecution.readModifyWriteOrderComputable.value.isConsistent())
            return AtomicityViolation()
        extendedExecution.writesBeforeOrderComputable.apply {
            initialize(); compute()
        }
        if (!extendedExecution.writesBeforeOrderComputable.value.isConsistent())
            return ReleaseAcquireInconsistency()
        extendedExecution.coherenceOrderComputable.apply {
            initialize(); compute()
        }
        if (!extendedExecution.coherenceOrderComputable.value.isConsistent())
            return SequentialConsistencyCoherenceViolation()
        val executionOrder = extendedExecution.executionOrderComputable.value.ensure { it.isConsistent() }
        SequentialConsistencyReplayer(1 + execution.maxThreadID).ensure {
            it.replay(executionOrder.ordering) != null
        }
        return SequentialConsistencyWitness.create(executionOrder.ordering)
    }

    private fun checkByCoherenceOrdering(
        execution: Execution<AtomicThreadEvent>,
        executionIndex: AtomicMemoryAccessEventIndex,
        rmwChainsStorage: ReadModifyWriteOrder,
        wbRelation: WritesBeforeOrder,
    ): ConsistencyVerdict<SequentialConsistencyWitness> {
        val writesOrder = causalityOrder union wbRelation
        val executionOrderComputable = computable {
            ExecutionOrder(execution, executionIndex, Relation.empty())
        }
        val coherence = CoherenceOrder(execution, executionIndex, rmwChainsStorage, writesOrder,
                executionOrder = executionOrderComputable
            )
            .apply { initialize(); compute() }
        if (!coherence.isConsistent())
            return SequentialConsistencyCoherenceViolation()
        val executionOrder = executionOrderComputable.value.ensure { it.isConsistent() }
        SequentialConsistencyReplayer(1 + execution.maxThreadID).ensure {
            it.replay(executionOrder.ordering) != null
        }
        return SequentialConsistencyWitness.create(executionOrder.ordering)
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

class SequentialConsistencyOrder(
    val execution: Execution<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val memoryAccessOrder: Relation<AtomicThreadEvent>,
) : Relation<AtomicThreadEvent>, Computable {

    // TODO: make cached delegate?
    private var consistent = true

    private var relation: RelationMatrix<AtomicThreadEvent>? = null

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean =
        relation?.invoke(x, y) ?: false

    fun isConsistent(): Boolean {
        return consistent
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
                consistent = false
                return@fixpoint
            }
            coherenceClosure()
            transitiveClosure()
        }
    }

    override fun invalidate() {
        consistent = true
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

class ExecutionOrder(
    val execution: Execution<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val approximation: Relation<AtomicThreadEvent>,
) : Relation<AtomicThreadEvent>, Computable {

    private var consistent = true

    private val _ordering = mutableListOf<AtomicThreadEvent>()

    val ordering: List<AtomicThreadEvent>
        get() = _ordering

    private val constraints = Relation<AtomicThreadEvent> { x, y ->
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

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        TODO("Not yet implemented")
    }

    fun isConsistent(): Boolean =
        consistent

    override fun compute() {
        check(_ordering.isEmpty())
        // TODO: although we have to add these additional ordering constraints here,
        //  it is not a completely sound way to enforce additional atomicity constraints;
        //  instead we can ensure additional atomicity constraints
        //  by reordering some events after topological sorting
        val relation = approximation union constraints
        // TODO: optimization --- we can build graph only for a subset of events, excluding:
        //  - non-blocking request events
        //  - events accessing race-free locations
        //  - what else?
        //  and then insert them back into the topologically sorted list
        val graph = execution.buildGraph(relation)
        val ordering = topologicalSorting(graph)
        if (ordering == null) {
            consistent = false
            return
        }
        this._ordering.addAll(ordering)
    }

    override fun invalidate() {
        consistent = true
    }

    override fun reset() {
        _ordering.clear()
        invalidate()
    }

}