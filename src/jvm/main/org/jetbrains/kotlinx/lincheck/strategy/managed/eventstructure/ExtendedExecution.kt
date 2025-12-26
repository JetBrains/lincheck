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
    val coherenceOrder: Relation<AtomicThreadEvent>

    /**
     * The extended coherence (eco) relation of the execution
     *
     * @see ExtendedCoherenceOrder
     */
    val extendedCoherence: Relation<AtomicThreadEvent>

    /**
     * The sequential consistency order (sc) of the execution.
     *
     * @see SequentialConsistencyOrder
     */
    val sequentialConsistencyOrder: Relation<AtomicThreadEvent>

    /**
     * The execution order (xo) of the execution.
     *
     * @see ExecutionOrder
     */
    val executionOrder: Relation<AtomicThreadEvent>

    val inconsistency: Inconsistency?
}

/**
 * Represents a mutable extended execution, which extends the regular execution
 * with additional information and supports modification.
 *
 * The mutable extended execution allows adding events to the execution,
 * or resetting the execution to a new set of events,
 * rebuilding the auxiliary data structures accordingly.
 */
interface MutableExtendedExecution : ExtendedExecution, MutableExecution<AtomicThreadEvent> {

    override val memoryAccessEventIndex: MutableAtomicMemoryAccessEventIndex

    val readModifyWriteOrderComputable: ComputableNode<ReadModifyWriteOrder>

    val writesBeforeOrderComputable: ComputableNode<WritesBeforeOrder>

    val coherenceOrderComputable: ComputableNode<CoherenceOrder>

    val extendedCoherenceComputable: ComputableNode<ExtendedCoherenceOrder>

    val sequentialConsistencyOrderComputable: ComputableNode<SequentialConsistencyOrder>

    val executionOrderComputable: ComputableNode<ExecutionOrder>

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

    fun checkConsistency(): Inconsistency?
}

fun ExtendedExecution(): ExtendedExecution =
    MutableExtendedExecution()

fun MutableExtendedExecution(): MutableExtendedExecution =
    ExtendedExecutionImpl(ResettableExecution())


/* private */ class ExtendedExecutionImpl(
    val execution: ResettableExecution
) : MutableExtendedExecution, MutableExecution<AtomicThreadEvent> by execution {

    override val memoryAccessEventIndex =
        MutableAtomicMemoryAccessEventIndex().apply { index(execution) }

    override val readModifyWriteOrderComputable = computable { ReadModifyWriteOrder(execution) }

    override val readModifyWriteOrder: Relation<AtomicThreadEvent> by readModifyWriteOrderComputable

    override val writesBeforeOrderComputable = computable {
            WritesBeforeOrder(
                execution,
                memoryAccessEventIndex,
                readModifyWriteOrderComputable.value,
                causalityOrder
            )
        }
        .dependsOn(readModifyWriteOrderComputable, soft = true, invalidating = true)

    override val writesBeforeOrder: Relation<AtomicThreadEvent> by writesBeforeOrderComputable

    override val coherenceOrderComputable = computable {
            CoherenceOrder(
                execution,
                memoryAccessEventIndex,
                readModifyWriteOrderComputable.value,
                causalityOrder union writesBeforeOrderComputable.value, // TODO: add eco or sc?
            )
        }
        .dependsOn(readModifyWriteOrderComputable, soft = true, invalidating = true)
        .dependsOn(writesBeforeOrderComputable, soft = true, invalidating = true)

    override val coherenceOrder: Relation<AtomicThreadEvent> by coherenceOrderComputable

    override val extendedCoherenceComputable = computable {
            ExtendedCoherenceOrder(
                execution,
                memoryAccessEventIndex,
                causalityOrder union writesBeforeOrderComputable.value // TODO: add coherence
            )
        }
        .dependsOn(writesBeforeOrderComputable, soft = true, invalidating = true)
        .apply {
            // add reference to coherence order, so once it is computed
            // it can force-set the extended coherence order
            coherenceOrderComputable.value.extendedCoherenceOrder = this
        }

    override val extendedCoherence: Relation<AtomicThreadEvent> by extendedCoherenceComputable

    override val sequentialConsistencyOrderComputable = computable {
            SequentialConsistencyOrder(
                execution,
                memoryAccessEventIndex,
                causalityOrder union extendedCoherenceComputable.value,
                // TODO: refine eco order after sc order computation (?)
            )
        }
        .dependsOn(extendedCoherenceComputable, soft = true, invalidating = true)

    override val sequentialConsistencyOrder: Relation<AtomicThreadEvent> by sequentialConsistencyOrderComputable

    override val executionOrderComputable = computable {
            ExecutionOrder(
                execution,
                memoryAccessEventIndex,
                causalityOrder union extendedCoherence, // TODO: add sc order
            )
        }
        .dependsOn(extendedCoherenceComputable, soft = true, invalidating = true)
        .apply {
            // add reference to coherence order, so once it is computed
            // it can force-set the execution order
            coherenceOrderComputable.value.executionOrder = this
        }

    override val executionOrder: Relation<AtomicThreadEvent> by executionOrderComputable

    private val consistencyChecker = aggregateConsistencyCheckers(
        execution = this,
        listOf<AtomicEventConsistencyChecker>(
            ReadModifyWriteAtomicityChecker(execution = this),

            IncrementalSequentialConsistencyChecker(
                execution = this,
                checkReleaseAcquireConsistency = true,
                approximateSequentialConsistency = false
            )
        ),
        listOf(),
    )

    private val trackers = listOf(
        memoryAccessEventIndex.incrementalTracker(),
        consistencyChecker.incrementalTracker(),
    )

    override val inconsistency: Inconsistency?
        get() = consistencyChecker.state.inconsistency

    override fun checkConsistency(): Inconsistency? {
        return consistencyChecker.check()
    }

    override fun add(event: AtomicThreadEvent) {
        execution.add(event)
        for (tracker in trackers)
            tracker.onAdd(event)
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

private typealias AtomicEventConsistencyChecker =
        IncrementalConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution>

private fun AtomicEventConsistencyChecker.incrementalTracker(): ExtendedExecutionTracker {
    return object : ExtendedExecutionTracker {
        override fun onAdd(event: AtomicThreadEvent) {
            check(event)
        }

        override fun onReset(execution: MutableExtendedExecution) {
            reset(execution)
        }
    }
}

// private fun<I> ComputableNode<I>.incrementalTracker(): ExecutionTracker<AtomicThreadEvent>
//     where I : Computable,
//           I : Incremental<AtomicThreadEvent>
// {
//     return object : ExecutionTracker<AtomicThreadEvent> {
//         override fun onAdd(event: AtomicThreadEvent) {
//             if (computed) value.add(event)
//         }
//
//         override fun onReset(execution: Execution<AtomicThreadEvent>) {
//             reset()
//         }
//     }
// }
//
// private fun ComputableNode<*>.resettingTracker(): ExecutionTracker<AtomicThreadEvent> {
//     return object : ExecutionTracker<AtomicThreadEvent> {
//         override fun onAdd(event: AtomicThreadEvent) {
//             reset()
//         }
//
//         override fun onReset(execution: Execution<AtomicThreadEvent>) {
//             reset()
//         }
//     }
// }