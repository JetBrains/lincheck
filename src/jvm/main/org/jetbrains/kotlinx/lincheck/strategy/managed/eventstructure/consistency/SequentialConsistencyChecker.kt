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
import kotlin.collections.*


abstract class SequentialConsistencyViolation : Inconsistency()

class SequentialConsistencyChecker(
    val checkReleaseAcquireConsistency: Boolean = true,
    val approximateSequentialConsistency: Boolean = true,
    val checkCoherence: Boolean = true,
) : ConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution> {

    private val releaseAcquireChecker : ReleaseAcquireConsistencyChecker? =
        if (checkReleaseAcquireConsistency) ReleaseAcquireConsistencyChecker() else null

    private val coherenceChecker : CoherenceChecker? =
        if (checkCoherence) CoherenceChecker() else null

    override fun check(execution: MutableExtendedExecution): Inconsistency? {
        check(!execution.executionOrderComputable.computed)
        releaseAcquireChecker?.check(execution)
            ?.let { return it }
        coherenceChecker?.check(execution)
            ?.let { return it }
        check(execution.executionOrderComputable.computed)
        val executionOrder = execution.executionOrderComputable.value
            .ensure { it.isConsistent() }
        SequentialConsistencyReplayer(1 + execution.maxThreadID).ensure {
            it.replay(executionOrder.ordering) != null
        }
        return null
    }

    /*
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
    */


    // private fun checkByCoherenceOrdering(
    //     execution: Execution<AtomicThreadEvent>,
    //     executionIndex: AtomicMemoryAccessEventIndex,
    //     rmwChainsStorage: ReadModifyWriteOrder,
    //     wbRelation: WritesBeforeOrder,
    // ): ConsistencyVerdict<SequentialConsistencyWitness> {
    //     val writesOrder = causalityOrder union wbRelation
    //     val executionOrderComputable = computable {
    //         ExecutionOrder(execution, executionIndex, Relation.empty())
    //     }
    //     val coherence = CoherenceOrder(execution, executionIndex, rmwChainsStorage, writesOrder,
    //             executionOrder = executionOrderComputable
    //         )
    //         .apply { initialize(); compute() }
    //     if (!coherence.isConsistent())
    //         return SequentialConsistencyCoherenceViolation()
    //     val executionOrder = executionOrderComputable.value.ensure { it.isConsistent() }
    //     SequentialConsistencyReplayer(1 + execution.maxThreadID).ensure {
    //         it.replay(executionOrder.ordering) != null
    //     }
    //     return SequentialConsistencyWitness.create(executionOrder.ordering)
    // }

}

class CoherenceViolation : SequentialConsistencyViolation() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Sequential consistency coherence violation detected"
    }
}

class IncrementalSequentialConsistencyChecker(
    execution: MutableExtendedExecution,
    checkReleaseAcquireConsistency: Boolean = true,
    approximateSequentialConsistency: Boolean = true
) : AbstractPartialIncrementalConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution>(
    execution = execution,
    checker = SequentialConsistencyChecker(
        checkReleaseAcquireConsistency,
        approximateSequentialConsistency,
    )
) {

    private val lockConsistencyChecker = LockConsistencyChecker()

    override fun doIncrementalCheck(event: AtomicThreadEvent): ConsistencyVerdict {
        check(state is ConsistencyVerdict.Consistent)
        check(execution.executionOrderComputable.computed)
        resetRelations()
        val executionOrder = execution.executionOrderComputable.value
        if (!executionOrder.isConsistentExtension(event)) {
            // if we end up in an unknown state, reset the execution order,
            // so it can be re-computed by the full consistency check
            execution.executionOrderComputable.reset()
            return ConsistencyVerdict.Unknown
        }
        executionOrder.add(event)
        return ConsistencyVerdict.Consistent
    }

    override fun doLightweightCheck(): ConsistencyVerdict {
        // TODO: extract into separate checker
        lockConsistencyChecker.check(execution)?.let { inconsistency ->
            return ConsistencyVerdict.Inconsistent(inconsistency)
        }
        // check by trying to replay execution order
        if (state == ConsistencyVerdict.Consistent) {
            check(execution.executionOrderComputable.computed)
            val replayer = SequentialConsistencyReplayer(1 + execution.maxThreadID)
            val executionOrder = execution.executionOrderComputable.value
            if (replayer.replay(executionOrder.ordering) != null) {
                // if replay is successful, return "consistent" verdict
                return ConsistencyVerdict.Consistent
            }
        }
        // if we end up in an unknown state, reset the execution order,
        // so it can be re-computed by the full consistency check
        execution.executionOrderComputable.reset()
        return ConsistencyVerdict.Unknown
    }

    override fun doReset(): ConsistencyVerdict {
        resetRelations()
        execution.executionOrderComputable.apply {
            reset()
            // set state to `computed`,
            // so we can push the events into the execution order
            setComputed()
        }
        for (event in execution.enumerationOrderSorted()) {
            val verdict = doIncrementalCheck(event)
            if (verdict is ConsistencyVerdict.Unknown) {
                return ConsistencyVerdict.Unknown
            }
        }
        return ConsistencyVerdict.Consistent
    }

    private fun ExecutionOrder.isConsistentExtension(event: AtomicThreadEvent): Boolean {
        val last = ordering.lastOrNull()
        val label = event.label
        // TODO: for this check to be more robust,
        //   can we generalize it to work with the arbitrary aggregation algebra?
        return when {
            label is ReadAccessLabel && label.isResponse ->
                // TODO: also check that read reads-from some consistent write:
                //   e.g. the globally last write, or the last observed write
                event.isValidResponse(last!!)

            label is WriteAccessLabel && label.isExclusive ->
                event.isWritePartOfAtomicUpdate(last!!)

            else -> true
        }
    }

    // TODO: move to corresponding individual consistency checkers
    private fun resetRelations() {
        execution.writesBeforeOrderComputable.reset()
        execution.coherenceOrderComputable.reset()
        execution.extendedCoherenceComputable.reset()
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
            x.label.isRequest && x.label is WaitLabel ->
                (y == execution.getResponse(x)?.notifiedBy)

            else -> false
        }
    }

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        TODO("Not yet implemented")
    }

    fun isConsistent(): Boolean =
        // TODO: embed failure state into ComputableNode state machine?
        consistent

    fun add(event: AtomicThreadEvent) {
        check(consistent)
        _ordering.add(event)
    }

    override fun compute() {
        check(_ordering.isEmpty())
        val relation = approximation union constraints
        // construct aggregated execution consisting of atomic events
        // to incorporate the atomicity constraints during the search for topological sorting
        val (aggregatedExecution, _) = execution.aggregate(ThreadAggregationAlgebra.aggregator())
        val aggregatedRelation = relation.existsLifting()
        // TODO: optimization --- we can build graph only for a subset of events, excluding:
        //  - non-blocking request events
        //  - events accessing race-free locations
        //  - what else?
        //  and then insert them back into the topologically sorted list
        val graph = aggregatedExecution.buildGraph(aggregatedRelation)
        val ordering = topologicalSorting(graph)
        if (ordering == null) {
            consistent = false
            return
        }
        this._ordering.addAll(ordering.flatMap { it.events })
    }

    override fun invalidate() {
        consistent = true
    }

    override fun reset() {
        _ordering.clear()
        invalidate()
    }

}