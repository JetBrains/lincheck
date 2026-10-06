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
import org.jetbrains.lincheck.util.collections.SortedList


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

fun ExtendedExecution(memoryModel: MemoryModel): ExtendedExecution =
    MutableExtendedExecution(memoryModel)

fun MutableExtendedExecution(memoryModel: MemoryModel): MutableExtendedExecution =
    ExtendedExecutionImpl(ResettableExecution(), memoryModel)


/* private */ class ExtendedExecutionImpl(
    val execution: ResettableExecution,
    val memoryModel: MemoryModel,
) : MutableExtendedExecution, MutableExecution<AtomicThreadEvent> by execution {

    override val memoryAccessEventIndex =
        MutableAtomicMemoryAccessEventIndex().apply { index(execution) }

    private val consistencyChecker = FullConsistencyChecker(this, memoryAccessEventIndex, memoryModel)

    private val trackers = listOf(
        memoryAccessEventIndex.incrementalTracker(),
        consistencyChecker
    )

    override val inconsistency: Inconsistency?
        get() = consistencyChecker.inconsistency

    override fun checkConsistency(): Inconsistency? {
        return consistencyChecker.completeCheck()
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

typealias ExtendedExecutionTracker = ExecutionTracker<AtomicThreadEvent, MutableExtendedExecution>

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