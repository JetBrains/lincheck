/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

import org.jetbrains.kotlinx.lincheck.strategy.managed.SeqCstMemoryTracker

private typealias ExecutionCounter = IntArray

private fun SeqCstMemoryTracker.replay(iThread: Int, label: EventLabel): SeqCstMemoryTracker? {
    return when {

        label is AtomicMemoryAccessLabel && label.isRead -> this.takeIf {
            label.value == readValue(iThread, label.memId, label.kClass)
        }

        label is AtomicMemoryAccessLabel && label.isWrite -> copy().apply {
            writeValue(iThread, label.memId, label.value, label.kClass)
        }

        label is ReadModifyWriteMemoryAccessLabel -> copy().takeIf {
            it.compareAndSet(iThread, label.memId, label.readLabel.value, label.writeLabel.value, label.kClass)
        }

        label is ThreadEventLabel -> this
        label is InitializationLabel -> this
        else -> unreachable()
    }
}

// TODO: what information we can store to point out to the reason of violation?
class SequentialConsistencyViolation : Inconsistency()

class SequentialConsistencyChecker(
    val approximateSequentialConsistencyRelation: Boolean = true
) : ConsistencyChecker {

    private data class State(
        val counter: ExecutionCounter,
        val memoryTracker: SeqCstMemoryTracker
    ) {
        companion object {
            fun initial(execution: Execution): State {
                return State(
                    counter = IntArray(execution.maxThreadId),
                    memoryTracker = SeqCstMemoryTracker(),
                )
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is State) return false
            return counter.contentEquals(other.counter) &&
                   memoryTracker == other.memoryTracker
        }

        override fun hashCode(): Int {
            return 31 * counter.contentHashCode() + memoryTracker.hashCode()
        }
    }

    private class Context(val execution: Execution, val covering: Covering<Event>) {

        fun State.covered(event: Event): Boolean =
            event.threadPosition < (counter[event.threadId] ?: 0)

        fun State.coverable(event: Event): Boolean =
            covering(event).all { covered(it) }

        val State.isTerminal: Boolean
            get() = counter.withIndex().all { (threadId, position) ->
                position == execution.getThreadSize(threadId)
            }

        fun State.transition(threadId: Int): State? {
            val position = counter[threadId]
            val (label, aggregated) = execution.getAggregatedLabel(threadId, position)
                ?.takeIf { (_, events) -> events.all { coverable(it) } }
                ?: return null
            val memoryTracker = if (label.isTotal) {
                this.memoryTracker.replay(threadId, label) ?: return null
            } else {
                require(label.isRequest && position == execution.lastPosition(threadId))
                this.memoryTracker
            }
            return State(
                memoryTracker = memoryTracker,
                counter = this.counter.copyOf().also {
                    it[threadId] += aggregated.size
                },
            )
        }

        fun State.transitions() : List<State> {
            val states = arrayListOf<State>()
            for (threadId in counter.indices) {
                transition(threadId)?.let { states.add(it) }
            }
            return states
        }

    }

    private fun saturateApproximateSequentialConsistencyRelation(execution: Execution, relation: RelationMatrix<Event>): Boolean {
        var changed = false
        readLoop@for (read in execution) {
            if (!(read.label is MemoryAccessLabel && read.label.isRead && !read.label.isRequest))
                continue
            val readFrom = read.readsFrom
            writeLoop@for (write in execution) {
                if (!write.label.isWriteAccessTo(read.label.memId))
                    continue
                if (relation(write, read) && write != readFrom) {
                    changed = changed || relation.change(write, readFrom, true)
                }
                if (relation(readFrom, write) && read != write) {
                    changed = changed || relation.change(read, write, true)
                }
            }
        }
        return changed
    }

    private fun buildApproximateSequentialConsistencyRelation(execution: Execution): RelationMatrix<Event> {
        val relation = RelationMatrix(execution, execution.buildIndexer()) { x, y ->
            causalityOrder.lessThan(x, y)
        }
        do {
            val changed =
                saturateApproximateSequentialConsistencyRelation(execution, relation) &&
                relation.transitiveClosure()
        } while (changed)
        return relation
    }

    private fun checkByReplaying(execution: Execution, covering: Covering<Event>): Boolean {
        // TODO: this is just a DFS search.
        //  In fact, we can generalize this algorithm to
        //  two arbitrary labelled transition systems by taking their product LTS
        //  and trying to find a trace in this LTS leading to terminal state.
        val context = Context(execution, covering)
        val initState = State.initial(execution)
        val stack = ArrayDeque(listOf(initState))
        val visited = mutableSetOf(initState)
        with(context) {
            while (stack.isNotEmpty()) {
                val state = stack.removeLast()
                // TODO: maybe we should return more information than just success
                //  (e.g. path leading to terminal state)?
                if (state.isTerminal) return true
                state.transitions().filter { it !in visited }.forEach {
                    visited.add(it)
                    stack.addLast(it)
                }
            }
            return false
        }
    }

    override fun check(execution: Execution): Inconsistency? {
        val covering = if (!approximateSequentialConsistencyRelation)
            externalCausalityCovering
        else {
            val relation = buildApproximateSequentialConsistencyRelation(execution)
            if (!relation.isIrreflexive()) {
                return SequentialConsistencyViolation()
            }
            relation.apply {
                transpose()
                transitiveReduction()
                remove { x, y -> x.threadId == y.threadId }
            }
            relation.covering()
        }

        return if (!checkByReplaying(execution, covering))
            SequentialConsistencyViolation()
            else null
    }

}