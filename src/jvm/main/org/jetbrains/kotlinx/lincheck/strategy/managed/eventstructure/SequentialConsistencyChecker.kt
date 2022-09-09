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

private typealias ExecutionCounter = MutableMap<Int, Int>

private fun SeqCstMemoryTracker.replay(label: EventLabel): SeqCstMemoryTracker? {
    check(label.isTotal)
    return when {
        label is AtomicMemoryAccessLabel && label.isRead ->
            copy().takeIf {
                readExpectedValue(label.threadId, label.memId, label.value, label.typeDescriptor)
            }

        label is AtomicMemoryAccessLabel && label.isWrite ->
            copy().apply {
                writeValue(label.threadId, label.memId, label.value, label.typeDescriptor)
            }

        label is ReadModifyWriteMemoryAccessLabel ->
            copy().takeIf {
                compareAndSet(label.threadId, label.memId, label.readValue, label.writeValue, label.typeDescriptor)
            }

        label is ThreadEventLabel -> this
        label is InitializationLabel -> this
        else -> unreachable()
    }
}

// TODO: what information we can store to point out to the reason of violation?
class SequentialConsistencyViolation : Inconsistency()

class SequentialConsistencyChecker : ConsistencyChecker {

    private data class State(
        val execution: Execution,
        val covering: Covering<Event>,
        val counter: ExecutionCounter,
        val memoryTracker: SeqCstMemoryTracker
    ) {
        companion object {
            fun initial(execution: Execution, covering: Covering<Event>): State {
                var memoryTracker = SeqCstMemoryTracker()
                return State(
                    execution = execution,
                    covering = covering,
                    memoryTracker = memoryTracker,
                    counter = execution.threads.associateWith { 0 }.toMutableMap()
                )
            }
        }

        fun covered(event: Event): Boolean =
            event.threadPosition < (counter[event.threadId] ?: 0)

        fun coverable(events: List<Event>): Boolean =
            events.all { event -> covering(event).all {
                covered(it) || it in events
            }}

        fun coverable(event: Event): Boolean =
            coverable(listOf(event))

        val isTerminal: Boolean
            get() = counter.all { (threadId, position) ->
                position == execution.getThreadSize(threadId)
            }
    }

    private val transitions = AdjacencyList<State, EventLabel> { state ->
        state.counter.mapNotNull { (threadId, position) ->
            val (event, aggregated) = state.execution.getAggregatedEvent(threadId, position)
                ?.takeIf { (_, events) -> state.coverable(events) }
                ?: return@mapNotNull null
            val memoryTracker = state.memoryTracker.replay(event.label)
                ?: return@mapNotNull null
            event.label to State(
                execution = state.execution,
                covering = state.covering,
                counter = state.counter.toMutableMap().apply {
                    update(event.threadId, default = 0) { it + aggregated.size }
                },
                memoryTracker
            )
        }
    }

    private fun checkByReplaying(execution: Execution, covering: Covering<Event>): Boolean {
        // TODO: this is just a DFS search.
        //  In fact, we can generalize this algorithm to
        //  two arbitrary labelled transition systems by taking their product LTS
        //  and trying to find a trace in this LTS leading to terminal state.
        val initState = State.initial(execution, covering)
        val stack = ArrayDeque(listOf(initState))
        while (stack.isNotEmpty()) {
            val state = stack.removeLast()
            // TODO: maybe we should return more information than just success
            //  (e.g. path leading to terminal state)?
            if (state.isTerminal) return true
            transitions(state).forEach { (_, state) -> stack.addLast(state) }
        }
        return false
    }

    override fun check(execution: Execution): Inconsistency? {
        return if (!checkByReplaying(execution, causalityCovering))
            SequentialConsistencyViolation()
            else null
    }

}