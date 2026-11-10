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
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingMonitorTracker
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.Covering
import org.jetbrains.lincheck.util.ensureTrue
import org.jetbrains.lincheck.util.unreachable

fun checkByReplaying(
    execution: Execution<HyperThreadEvent>,
    covering: Covering<HyperThreadEvent>
): Inconsistency? {
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
            if (state.isTerminal) {
                return null
                // return SequentialConsistencyWitness.create(
                //     executionOrder = state.history.flatMap { it.events }
                // )
            }
            state.transitions().forEach {
                val unvisited = visited.add(it)
                if (unvisited) {
                    stack.addLast(it)
                }
            }
        }
        return SequentialConsistencyReplayViolation()
    }
}

class SequentialConsistencyReplayViolation : SequentialConsistencyViolation() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Sequential consistency replay violation detected"
    }
}

internal data class SequentialConsistencyReplayer(
    val memoryView: MutableMap<MemoryLocation, Event> = mutableMapOf(),
    val monitorTracker: ModelCheckingMonitorTracker = ModelCheckingMonitorTracker(),
    val monitorMapping: MutableMap<ObjectNumber, Any> = mutableMapOf(),
) {

    /** Whether replaying [event] can change this state, including its monitor mapping. */
    fun isMutable(event: AtomicThreadEvent): Boolean = when (val label = event.label) {
        is WriteAccessLabel, is UnlockLabel, is NotifyLabel -> true
        is LockLabel -> label.isResponse
        else -> false
    }

    fun replay(event: AtomicThreadEvent): SequentialConsistencyReplayer? {
        val state = if (isMutable(event)) copy() else this
        return state.replayInPlace(event)
    }

    /** Replays into this state; a failed replay may leave it partially modified. */
    fun replayInPlace(event: AtomicThreadEvent): SequentialConsistencyReplayer? {
        val label = event.label
        return when {

            label is ReadAccessLabel && label.isRequest ->
                this

            label is ReadAccessLabel && label.isResponse ->
                this.takeIf {
                    // TODO: do we really need this `if` here?
                    if (event.readsFrom.label is WriteAccessLabel)
                        memoryView[label.location] == event.readsFrom
                    else memoryView[label.location] == null
                }

            label is WriteAccessLabel ->
                this.apply { memoryView[label.location] = event }

            label is LockLabel && label.isRequest ->
                this

            label is LockLabel && label.isResponse && !label.isSynthetic -> {
                val monitor = getMonitor(label.mutexID)
                if (this.monitorTracker.canAcquireMonitor(event.threadId, monitor)) {
                    this.apply { monitorTracker.acquireMonitor(event.threadId, monitor).ensureTrue() }
                } else null
            }

            label is UnlockLabel && !label.isSynthetic ->
                this.apply { monitorTracker.releaseMonitor(event.threadId, getMonitor(label.mutexID)) }

            label is WaitLabel ->
                // Notification delivery and monitor reacquisition are separate events,
                // so we don't need to replay monitor re-acquiring here.
                this

            label is NotifyLabel ->
                this.apply { monitorTracker.notify(event.threadId, getMonitor(label.mutexID), label.isBroadcast) }

            // auxiliary unlock/lock events inserted before/after wait events
            label is LockLabel && label.isSynthetic -> {
                val monitor = getMonitor(label.mutexID)
                if (this.monitorTracker.canAcquireMonitor(event.threadId, monitor)) {
                    this.takeIf { !it.monitorTracker.waitOnMonitor(event.threadId, monitor) }
                } else null
            }
            label is UnlockLabel && label.isSynthetic ->
                this.apply { monitorTracker.waitOnMonitor(event.threadId, getMonitor(label.mutexID)).ensureTrue() }

            label is InitializationLabel -> this
            label is ObjectAllocationLabel -> this
            label is ThreadEventLabel -> this
            // TODO: do we need to care about parking?
            label is ParkingEventLabel -> this
            label is CoroutineLabel -> this
            label is ActorLabel -> this
            label is RandomLabel -> this
            label is FenceLabel -> this

            else -> unreachable()

        }
    }

    fun replay(events: Iterable<AtomicThreadEvent>): SequentialConsistencyReplayer? {
        var replayer = this
        for (event in events) {
            replayer = replayer.replay(event) ?: return null
        }
        return replayer
    }

    fun replay(event: HyperThreadEvent): SequentialConsistencyReplayer? {
        return replay(event.events)
    }

    fun replayInPlace(events: Iterable<AtomicThreadEvent>): SequentialConsistencyReplayer? {
        for (event in events) {
            replayInPlace(event) ?: return null
        }
        return this
    }

    fun replayInPlace(event: HyperThreadEvent): SequentialConsistencyReplayer? {
        return replayInPlace(event.events)
    }

    fun copy(): SequentialConsistencyReplayer =
        SequentialConsistencyReplayer(
            memoryView = memoryView.toMutableMap(),
            monitorTracker = monitorTracker.copy(),
            monitorMapping = monitorMapping.toMutableMap(),
        )

    private fun getMonitor(objID: ObjectNumber): Any {
        check(objID != NULL_OBJECT_NUMBER)
        return monitorMapping.computeIfAbsent(objID) { Any() }
    }

}

private data class State(
    val executionClock: MutableVectorClock,
    val replayer: SequentialConsistencyReplayer,
) {

    // TODO: move to Context
    var history: List<HyperThreadEvent> = listOf()
        private set

    constructor(
        executionClock: MutableVectorClock,
        replayer: SequentialConsistencyReplayer,
        history: List<HyperThreadEvent>,
    ) : this(executionClock, replayer) {
        this.history = history
    }

    companion object {
        fun initial(execution: Execution<HyperThreadEvent>) = State(
            executionClock = MutableVectorClock(),
            replayer = SequentialConsistencyReplayer(),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is State)
                && executionClock == other.executionClock
                && replayer == other.replayer
    }

    override fun hashCode(): Int {
        var result = executionClock.hashCode()
        result = 31 * result + replayer.hashCode()
        return result
    }

}

private class Context(val execution: Execution<HyperThreadEvent>, val covering: Covering<HyperThreadEvent>) {

    fun State.covered(event: HyperThreadEvent): Boolean =
        executionClock.observes(event)

    fun State.coverable(event: HyperThreadEvent): Boolean =
        covering.coverable(event, executionClock)

    val State.isTerminal: Boolean
        get() = executionClock.observes(execution)

    fun State.transition(threadId: Int): State? {
        val position = 1 + executionClock[threadId]
        val event = execution[threadId, position]
            ?.takeIf { coverable(it) }
            ?: return null
        val view = replayer.replay(event)
            ?: return null
        return State(
            replayer = view,
            history = this.history + event,
            executionClock = this.executionClock.copy().apply {
                increment(event.threadId)
            },
        )
    }

    fun State.transitions() : List<State> {
        val states = arrayListOf<State>()
        for (threadId in execution.threadIDs) {
            transition(threadId)?.let { states.add(it) }
        }
        return states
    }

}
