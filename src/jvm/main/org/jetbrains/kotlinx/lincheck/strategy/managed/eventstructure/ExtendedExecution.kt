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
import org.jetbrains.kotlinx.lincheck.utils.*


/**
 * Extended execution extends the regular execution with additional information,
 * such as various event indices and auxiliary relations.
 * This additional information is mainly used to maintain the consistency of the execution.
 *
 * @see Execution
 */
interface ExtendedExecution : Execution<AtomicThreadEvent> {

    /**
     * The writes-before (wb) order of the execution.
     *
     * @see WritesBeforeRelation
     */
    val writesBefore: Relation<AtomicThreadEvent>

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

    val executionOrder: Relation<AtomicThreadEvent>
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

    val rmwChainsStorageComputable: ComputableNode<ReadModifyWriteChainRelation>

    val writesBeforeComputable: ComputableNode<WritesBeforeRelation>

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
}

fun ExtendedExecution(nThreads: Int): ExtendedExecution =
    MutableExtendedExecution(nThreads)

fun MutableExtendedExecution(nThreads: Int): MutableExtendedExecution =
    ExtendedExecutionImpl(ResettableExecution(nThreads))


/* private */ class ExtendedExecutionImpl(
    val execution: ResettableExecution
) : MutableExtendedExecution, MutableExecution<AtomicThreadEvent> by execution {

    private val memoryAccessEventIndexComputable = computable { MutableAtomicMemoryAccessEventIndex(execution) }

    override val rmwChainsStorageComputable = computable { ReadModifyWriteChainRelation(execution) }

    override val writesBeforeComputable = computable {
            WritesBeforeRelation(
                execution,
                memoryAccessEventIndexComputable.value,
                rmwChainsStorageComputable.value,
                causalityOrder.lessThan
            )
        }
        .dependsOn(memoryAccessEventIndexComputable)
        .dependsOn(rmwChainsStorageComputable, soft = true, invalidating = true)

    override val writesBefore: Relation<AtomicThreadEvent> by writesBeforeComputable

    override val coherenceOrderComputable = computable {
            CoherenceOrder(
                execution,
                memoryAccessEventIndexComputable.value,
                rmwChainsStorageComputable.value,
                causalityOrder.lessThan union writesBeforeComputable.value, // TODO: add eco or sc?
            )
        }
        .dependsOn(memoryAccessEventIndexComputable)
        .dependsOn(rmwChainsStorageComputable, soft = true, invalidating = true)
        .dependsOn(writesBeforeComputable, soft = true, invalidating = true)

    override val coherenceOrder: Relation<AtomicThreadEvent> by coherenceOrderComputable

    override val extendedCoherenceComputable = computable {
            ExtendedCoherenceOrder(
                execution,
                memoryAccessEventIndexComputable.value,
                causalityOrder.lessThan union writesBeforeComputable.value // TODO: add coherence
            )
        }
        .dependsOn(memoryAccessEventIndexComputable)
        .dependsOn(writesBeforeComputable, soft = true, invalidating = true)
        .apply {
            // add reference to coherence order, so once it is computed
            // it can force-set the extended coherence order
            coherenceOrderComputable.value.extendedCoherenceOrder = this
        }

    override val extendedCoherence: Relation<AtomicThreadEvent> by extendedCoherenceComputable

    override val sequentialConsistencyOrderComputable = computable {
            SequentialConsistencyOrder(
                execution,
                memoryAccessEventIndexComputable.value,
                causalityOrder.lessThan union extendedCoherenceComputable.value,
                // TODO: refine eco order after sc order computation (?)
            )
        }
        .dependsOn(memoryAccessEventIndexComputable)
        .dependsOn(extendedCoherenceComputable, soft = true, invalidating = true)

    override val sequentialConsistencyOrder: Relation<AtomicThreadEvent> by sequentialConsistencyOrderComputable

    override val executionOrderComputable = computable {
            ExecutionOrder(
                execution,
                memoryAccessEventIndexComputable.value,
                causalityOrder.lessThan union extendedCoherence, // TODO: add sc order
            )
        }
        .dependsOn(memoryAccessEventIndexComputable)
        .dependsOn(extendedCoherenceComputable, soft = true, invalidating = true)
        .apply {
            // add reference to coherence order, so once it is computed
            // it can force-set the execution order
            coherenceOrderComputable.value.executionOrder = this
        }

    override val executionOrder: Relation<AtomicThreadEvent> by executionOrderComputable

    override fun reset(frontier: ExecutionFrontier<AtomicThreadEvent>) {
        execution.reset(frontier)
    }

}

/* private */ class ResettableExecution(nThreads: Int) : MutableExecution<AtomicThreadEvent> {

    private var execution = MutableExecution<AtomicThreadEvent>(nThreads)

    constructor(execution: MutableExecution<AtomicThreadEvent>) : this(0) {
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

}