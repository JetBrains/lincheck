/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingMonitorTracker
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.ensure

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
    val nThreads: Int,
    val memoryView: MutableMap<MemoryLocation, Event> = mutableMapOf(),
    val monitorTracker: ModelCheckingMonitorTracker = ModelCheckingMonitorTracker(),
    val monitorMapping: MutableMap<ObjectID, Any> = mutableMapOf()
) {

    fun replay(event: AtomicThreadEvent): SequentialConsistencyReplayer? {
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
                this.copy().apply { memoryView[label.location] = event }

            label is LockLabel && label.isRequest ->
                this

            label is LockLabel && label.isResponse && !label.isSynthetic -> {
                val monitor = getMonitor(label.mutexID)
                if (this.monitorTracker.acquireMonitor(event.threadId, monitor)) {
                    this.copy().apply { monitorTracker.acquireMonitor(event.threadId, monitor).ensure() }
                } else null
            }

            label is UnlockLabel && !label.isSynthetic ->
                this.copy().apply { monitorTracker.releaseMonitor(event.threadId, getMonitor(label.mutexID)) }

            label is WaitLabel && label.isRequest ->
                this.copy().apply { monitorTracker.waitOnMonitor(event.threadId, getMonitor(label.mutexID)).ensure() }

            label is WaitLabel && label.isResponse -> {
                val monitor = getMonitor(label.mutexID)
                if (this.monitorTracker.acquireMonitor(event.threadId, monitor)) {
                    this.copy().takeIf { !it.monitorTracker.waitOnMonitor(event.threadId, monitor) }
                } else null
            }

            label is NotifyLabel ->
                this.copy().apply { monitorTracker.notify(event.threadId, getMonitor(label.mutexID), label.isBroadcast) }

            // auxiliary unlock/lock events inserted before/after wait events
            label is LockLabel && label.isSynthetic ->
                this
            label is UnlockLabel && label.isSynthetic ->
                this

            label is InitializationLabel -> this
            label is ObjectAllocationLabel -> this
            label is ThreadEventLabel -> this
            // TODO: do we need to care about parking?
            label is ParkingEventLabel -> this
            label is CoroutineLabel -> this
            label is ActorLabel -> this
            label is RandomLabel -> this

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

    fun copy(): SequentialConsistencyReplayer =
        SequentialConsistencyReplayer(
            nThreads,
            memoryView.toMutableMap(),
            monitorTracker.copy(),
            monitorMapping.toMutableMap(),
        )

    private fun getMonitor(objID: ObjectID): Any {
        check(objID != NULL_OBJECT_ID)
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
            executionClock = MutableVectorClock(1 + execution.maxThreadID),
            replayer = SequentialConsistencyReplayer(1 + execution.maxThreadID),
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