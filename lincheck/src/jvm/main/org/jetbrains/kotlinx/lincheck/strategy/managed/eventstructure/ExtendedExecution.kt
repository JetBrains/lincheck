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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.*
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.Relation
import org.jetbrains.lincheck.util.collections.SortedList
import org.jetbrains.lincheck.util.union


/**
 * Extended execution extends the regular execution with additional information,
 * such as various event indices and auxiliary relations.
 * This additional information is mainly used to maintain the consistency of the execution.
 *
 * @see Execution
 */
interface ExtendedExecution : Execution<AtomicThreadEvent> {

    /**
     * Index for memory access events in the extended execution.
     *
     * @see AtomicMemoryAccessEventIndex
     */
    val memoryAccessEventIndex : AtomicMemoryAccessEventIndex

    /**
     * The read-modify-write order of the execution.
     *
     * @see ReadModifyWriteOrder
     */
    val readModifyWriteOrder: Relation<AtomicThreadEvent>

    /**
     * The writes-before (wb) order of the execution.
     *
     * @see WritesBeforeOrder
     */
    val writesBeforeOrder: Relation<AtomicThreadEvent>

    /**
     * The coherence (co) order of the execution
     *
     * @see CoherenceOrder
     */
    val coherenceOrder: CoherenceOrder

    /**
     * The exploration order of lock responses and unlocks.
     *
     * @see LockOrder
     */
    val lockOrder: LockOrder

    /**
     * The extended coherence (eco) relation of the execution
     *
     * @see ExtendedCoherenceOrder
     */
    val extendedCoherence: Relation<AtomicThreadEvent>

    /**
     * The sequential consistency approximation order of the execution.
     *
     * @see SequentialConsistencyApproximationOrder
     */
    val sequentialConsistencyApproximationOrder: Relation<AtomicThreadEvent>

    /**
     * The execution order (xo) of the execution.
     *
     * @see ExecutionOrder
     */
    val executionOrder: Relation<AtomicThreadEvent>

    /** Final-field observations and dependency views used by the Relaxed Java model. */
    val finalFieldsViewfront: FinalFieldsVisibilityRelation

    /**
     * Cached inconsistency of the execution (if any).
     */
    val inconsistency: Inconsistency?

}

/**
 * Represents a mutable extended execution, which extends the regular execution
 * with additional information and supports modification.
 *
 * The mutable extended execution allows adding events to the execution,
 * or resetting the execution to a new set of events,
 * rebuilding the auxiliary data structures accordingly.
 * Relations track these updates independently of consistency checks;
 * compute a cached relation before querying its result.
 */
interface MutableExtendedExecution : ExtendedExecution, MutableExecution<AtomicThreadEvent> {

    override val memoryAccessEventIndex: MutableAtomicMemoryAccessEventIndex

    override val readModifyWriteOrder: ReadModifyWriteOrder
    override val writesBeforeOrder: WritesBeforeOrder
    override val extendedCoherence: ExtendedCoherenceOrder
    override val sequentialConsistencyApproximationOrder: SequentialConsistencyApproximationOrder
    override val executionOrder: ExecutionOrder

    /**
     * Appends the missing events of [frontier], to current execution,
     * updating the relations where possible.
     *
     * Full consistency checks are deferred until the caller invokes [checkConsistency].
     *
     * Requires the current execution to be a prefix of [frontier] (see [isPrefixOf]).
     */
    fun extend(frontier: ExecutionFrontier<AtomicThreadEvent>)

    /**
     * Resets the mutable execution to contain the new set of events
     * and rebuilds all the auxiliary data structures accordingly.
     *
     * To ensure causal closure, the reset method takes the new set of events as an [ExecutionFrontier] object.
     * That is, after the reset, the execution will contain the events of the frontier as well as
     * all of its causal predecessors.
     *
     * @see ExecutionFrontier
     */
    fun reset(frontier: ExecutionFrontier<AtomicThreadEvent>)

    /**
     * Checks the consistency of the extended execution.
     */
    fun checkConsistency(): Inconsistency?

    fun filterInconsistent(
        event: AtomicThreadEvent,
        candidates: Sequence<AtomicThreadEvent>
    ): Sequence<AtomicThreadEvent>
}

fun ExtendedExecution(memoryModel: MemoryModel): ExtendedExecution =
    MutableExtendedExecution(memoryModel)

fun MutableExtendedExecution(memoryModel: MemoryModel): MutableExtendedExecution =
    ExtendedExecutionImpl(ResettableExecution(), memoryModel)


/* private */ class ExtendedExecutionImpl(
    val execution: ResettableExecution,
    val memoryModel: MemoryModel,
) : MutableExtendedExecution, MutableExecution<AtomicThreadEvent> by execution {

    val coherenceCausalOrder : Relation<AtomicThreadEvent>
        get() {
            return when (memoryModel) {
                MemoryModel.SequentialConsistency -> happensBeforeOrder
                MemoryModel.ReleaseAcquire -> happensBeforeSameLocationOrder
                MemoryModel.RelaxedJava -> happensBeforeSameLocationOrder
            }
        }

    override val memoryAccessEventIndex =
        MutableAtomicMemoryAccessEventIndex().apply { index(execution) }

    override val readModifyWriteOrder = ReadModifyWriteOrder(execution)

    override val writesBeforeOrder = WritesBeforeOrder(
        memoryAccessEventIndex, readModifyWriteOrder, happensBeforeOrder,
        memoryModel,
    )

    override val extendedCoherence = ExtendedCoherenceOrder(
        memoryAccessEventIndex, happensBeforeOrder union writesBeforeOrder,
    )

    override val sequentialConsistencyApproximationOrder = SequentialConsistencyApproximationOrder(
        execution, memoryAccessEventIndex, happensBeforeOrder union extendedCoherence,
    )

    override val coherenceOrder = CoherenceOrder(
        this,
        memoryAccessEventIndex,
        readModifyWriteOrder,
        happensBeforeOrder union writesBeforeOrder,
        coherenceCausalOrder,
        memoryModel,
    )

    override val lockOrder = LockOrder().apply { onReset(execution) }

    override val executionOrder = ExecutionOrder(
        execution,
        happensBeforeOrder union extendedCoherence,
    )

    override val finalFieldsViewfront = FinalFieldsVisibilityRelation(execution, memoryAccessEventIndex)

    private val consistencyChecker = when (memoryModel) {
        MemoryModel.SequentialConsistency -> SequentialConsistencyChecker(this)
        MemoryModel.ReleaseAcquire -> ReleaseAcquireConsistencyChecker(this)
        MemoryModel.RelaxedJava -> JmmConsistencyChecker(this)
    }

    private val trackers: List<ExtendedExecutionTracker> = listOfNotNull(
        memoryAccessEventIndex.incrementalTracker(),
        readModifyWriteOrder,
        writesBeforeOrder,
        coherenceOrder, // should be updated before the consistency checker,
                        // which checks its consistency flag
        lockOrder,
        extendedCoherence,
        sequentialConsistencyApproximationOrder,
        executionOrder
            // the execution order is used only for sequential consistency
            .takeIf { memoryModel == MemoryModel.SequentialConsistency },
        finalFieldsViewfront.takeIf { memoryModel == MemoryModel.RelaxedJava },
        consistencyChecker,
    )

    override val inconsistency: Inconsistency?
        get() = consistencyChecker.state.inconsistency

    override fun checkConsistency(): Inconsistency? {
        return consistencyChecker.check()
    }

    override fun filterInconsistent(
        event: AtomicThreadEvent,
        candidates: Sequence<AtomicThreadEvent>
    ): Sequence<AtomicThreadEvent> = consistencyChecker.filterInconsistent(event, candidates)

    override fun add(event: AtomicThreadEvent) {
        doAdd(event)
        // A restored RMW may read from an earlier write in the coherence order.
        // Rebuild its witness after all trackers have seen the write-part.
        if (event.label.isExclusiveWriteAccess() && !coherenceOrder.computed) {
            checkConsistency()
        }
    }

    private fun doAdd(event: AtomicThreadEvent) {
        execution.add(event)
        for (tracker in trackers) {
            tracker.onAdd(event)
        }
    }

    override fun extend(frontier: ExecutionFrontier<AtomicThreadEvent>) {
        val executionFrontier = execution.toFrontier()
        require(executionFrontier.isPrefixOf(frontier))

        val events = frontier.eventsDifference(executionFrontier).sortedBy { it.id }
        for (tid in frontier.threadIDs) {
            execution.registerThread(tid)
        }
        for (event in events) {
            doAdd(event)
        }
    }

    override fun reset(frontier: ExecutionFrontier<AtomicThreadEvent>) {
        execution.reset(frontier)
        for (tracker in trackers)
            tracker.onReset(this)
    }

    override fun toString(): String =
        execution.toString()

}

/* private */ class ResettableExecution() : MutableExecution<AtomicThreadEvent> {

    private var execution = MutableExecution<AtomicThreadEvent>()

    constructor(execution: MutableExecution<AtomicThreadEvent>) : this() {
        this.execution = execution
    }

    override val size: Int
        get() = execution.size

    override val threadMap: ThreadMap<SortedList<AtomicThreadEvent>>
        get() = execution.threadMap

    override fun isEmpty(): Boolean =
        execution.isEmpty()

    override fun registerThread(tid: ThreadId) {
        execution.registerThread(tid)
    }

    override fun add(event: AtomicThreadEvent) {
        execution.add(event)
    }

    fun reset(frontier: ExecutionFrontier<AtomicThreadEvent>) {
        execution = frontier.toMutableExecution()
    }

    override fun equals(other: Any?): Boolean =
        (other is ResettableExecution) && (execution == other.execution)

    override fun hashCode(): Int =
        execution.hashCode()

    override fun toString(): String =
        execution.toString()

}

private typealias ExtendedExecutionTracker = ExecutionTracker<AtomicThreadEvent, MutableExtendedExecution>

private fun MutableEventIndex<AtomicThreadEvent, *, *>.incrementalTracker(): ExtendedExecutionTracker {
    return object : ExtendedExecutionTracker {
        override fun onAdd(event: AtomicThreadEvent) {
            index(event)
        }

        override fun onReset(execution: MutableExtendedExecution) {
            reset()
            index(execution)
        }
    }
}
