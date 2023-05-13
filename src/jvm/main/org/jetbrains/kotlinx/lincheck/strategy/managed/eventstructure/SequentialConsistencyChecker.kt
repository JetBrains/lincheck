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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*

// TODO: what information can we store about the reason of violation?
class ReleaseAcquireConsistencyViolation: Inconsistency()

// TODO: what information can we store about the reason of violation?
data class SequentialConsistencyViolation(
    val phase: SequentialConsistencyCheckPhase
) : Inconsistency()

enum class SequentialConsistencyCheckPhase {
    PRELIMINARY, APPROXIMATION, REPLAYING
}

class SequentialConsistencyChecker(
    val checkReleaseAcquireConsistency: Boolean = true,
    val approximateSequentialConsistency: Boolean = true
) : ConsistencyChecker {

    override fun check(execution: Execution): Inconsistency? {
        // do basic preliminary checks
        checkLocks(execution)?.let { return it }
        // calculate writes-before relation if required
        val wbRelation = if (checkReleaseAcquireConsistency) {
            WritesBeforeRelation(execution).apply {
                saturate()?.let { return it }
            }
        } else null
        // calculate union of happens-before and writes-before relations
        val hbwbRelation = wbRelation?.let { wb ->
            executionRelation(execution, relation = causalityOrder.lessThan union wb)
        }
        // calculate approximation of sequential consistency order if required
        val scApproximationRelation = if (approximateSequentialConsistency) {
            val initialApproximation = hbwbRelation ?: causalityOrder.lessThan
            SequentialConsistencyRelation(execution, initialApproximation).apply {
                saturate()?.let { return it }
            }
        } else hbwbRelation
        // get dependency covering to guide the search
        val covering = scApproximationRelation?.buildExternalCovering() ?: externalCausalityCovering
        // check consistency by trying to replay execution using sequentially consistent abstract machine
        return checkByReplaying(execution, covering)
    }

    private fun checkLocks(execution: Execution): Inconsistency? {
        // maps unlock (or notify) event to its single matching lock (or wait) event;
        // if lock synchronizes-from initialization event,
        // then instead maps lock object itself to its first lock event
        val mapping = mutableMapOf<Any, Event>()
        for (event in execution) {
            if (event.label !is MutexLabel || !event.label.isResponse)
                continue
            if (!(event.label is LockLabel || event.label is WaitLabel))
                continue
            if (event.label is WaitLabel && (event.notifiedBy.label as NotifyLabel).isBroadcast)
                continue
            val key: Any = if (event.syncFrom != execution.rootEvent) event.syncFrom else event.label.mutex
            if (mapping.put(key, event) != null) {
                return SequentialConsistencyViolation(
                    phase = SequentialConsistencyCheckPhase.PRELIMINARY
                )
            }
        }
        return null
    }

    private fun checkByReplaying(execution: Execution, covering: Covering<Event>): Inconsistency? {
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
                if (state.isTerminal) return null
                state.transitions().forEach {
                    if (it !in visited) {
                        visited.add(it)
                        stack.addLast(it)
                    }
                }
            }
            return SequentialConsistencyViolation(
                phase = SequentialConsistencyCheckPhase.REPLAYING
            )
        }
    }

}

private data class SequentialConsistencyReplayer(
    val nThreads: Int,
    val memoryView: MutableMap<MemoryLocation, Event> = mutableMapOf(),
    val monitorTracker: MapMonitorTracker = MapMonitorTracker(nThreads),
) {

    fun replay(event: Event): SequentialConsistencyReplayer? {
        return when {

            event.label is ReadAccessLabel && event.label.isRequest ->
                this

            event.label is ReadAccessLabel && event.label.isResponse ->
                this.takeIf {
                    if (event.readsFrom.label !is InitializationLabel)
                         memoryView[event.label.location] == event.readsFrom
                    else memoryView[event.label.location] == null
                }

            event.label is WriteAccessLabel ->
                this.copy().apply { memoryView[event.label.location] = event }

            event.label is LockLabel && event.label.isRequest ->
                this

            event.label is LockLabel && event.label.isResponse ->
                if (this.monitorTracker.canAcquire(event.threadId, event.label.mutex)) {
                    this.copy().apply { monitorTracker.acquire(event.threadId, event.label.mutex).ensure() }
                } else null

            event.label is UnlockLabel ->
                this.copy().apply { monitorTracker.release(event.threadId, event.label.mutex) }

            event.label is WaitLabel && event.label.isRequest ->
                this.copy().apply { monitorTracker.wait(event.threadId, event.label.mutex).ensure() }

            event.label is WaitLabel && event.label.isResponse ->
                if (this.monitorTracker.canAcquire(event.threadId, event.label.mutex)) {
                    this.copy().takeIf { !it.monitorTracker.wait(event.threadId, event.label.mutex) }
                } else null

            event.label is NotifyLabel ->
                this.copy().apply { monitorTracker.notify(event.threadId, event.label.mutex, event.label.isBroadcast) }

            event.label is InitializationLabel -> this
            event.label is ThreadEventLabel -> this
            // TODO: do we need to care about parking?
            event.label is ParkingEventLabel -> this

            else -> unreachable()

        }
    }

    fun replay(hyperEvent: HyperEvent): SequentialConsistencyReplayer? {
        val events: List<Event> = when(hyperEvent) {
            is UnlockAndWait -> listOf(hyperEvent.waitRequestPart)
            is WakeUpAndTryLock -> listOf(hyperEvent.waitResponsePart)
            else -> hyperEvent.events
        }
        var replayer = this
        for (event in events) {
            replayer = replayer.replay(event) ?: return null
        }
        return replayer
    }

    fun copy(): SequentialConsistencyReplayer =
        SequentialConsistencyReplayer(
            nThreads,
            memoryView.toMutableMap(),
            monitorTracker.copy(),
        )

}

private typealias ExecutionCounter = IntArray

private data class State(
    val counter: ExecutionCounter,
    val replayer: SequentialConsistencyReplayer,
) {
    companion object {
        fun initial(execution: Execution) = State(
            counter = IntArray(execution.maxThreadId),
            replayer = SequentialConsistencyReplayer(execution.maxThreadId),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is State) return false
        return counter.contentEquals(other.counter) && replayer == other.replayer
    }

    override fun hashCode(): Int {
        return 31 * counter.contentHashCode() + replayer.hashCode()
    }

}

private class Context(val execution: Execution, val covering: Covering<Event>) {

    fun State.covered(event: Event): Boolean =
        event.threadPosition < counter[event.threadId]

    fun State.coverable(event: Event): Boolean =
        covering(event).all { covered(it) }

    val State.isTerminal: Boolean
        get() = counter.withIndex().all { (threadId, position) ->
            position == execution.getThreadSize(threadId)
        }

    fun State.transition(threadId: Int): State? {
        val position = counter[threadId]
        val atomicEvent = execution.nextAtomicEvent(threadId, position)
            ?.takeIf { atomicEvent -> atomicEvent.events.all { coverable(it) } }
            ?: return null
        val view = replayer.replay(atomicEvent) ?: return null
        return State(
            replayer = view,
            counter = this.counter.copyOf().also {
                it[threadId] += atomicEvent.events.size
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

private class SequentialConsistencyRelation(
    execution: Execution,
    initialApproximation: Relation<Event>
): ExecutionRelation(execution) {

    val relation = RelationMatrix(execution, indexer, initialApproximation)

    override fun invoke(x: Event, y: Event): Boolean =
        relation(x, y)

    fun saturate(): SequentialConsistencyViolation? {
        do {
            val changed = coherenceClosure() && relation.transitiveClosure()
            if (!relation.isIrreflexive())
                return SequentialConsistencyViolation(
                    phase = SequentialConsistencyCheckPhase.APPROXIMATION
                )
        } while (changed)
        return null
    }

    private fun coherenceClosure(): Boolean {
        var changed = false
        readLoop@for (read in execution) {
            if (!(read.label is ReadAccessLabel && read.label.isResponse))
                continue
            val readFrom = read.readsFrom
            writeLoop@for (write in execution) {
                if (!(write.label is WriteAccessLabel && write.label.location == read.label.location))
                    continue
                if (write != readFrom && relation(write, read) && !relation(write, readFrom)) {
                    relation[write, readFrom] = true
                    changed = true
                }
                if (read != write && relation(readFrom, write) && !relation(read, write)) {
                    relation[read, write] = true
                    changed = true
                }
            }
        }
        return changed
    }

}

private class WritesBeforeRelation(
    execution: Execution
): ExecutionRelation(execution) {

    private val readsMap: MutableMap<MemoryLocation, ArrayList<Event>> = mutableMapOf()

    private val writesMap: MutableMap<MemoryLocation, ArrayList<Event>> = mutableMapOf()

    private val relations: MutableMap<MemoryLocation, RelationMatrix<Event>> = mutableMapOf()

    init {
        var initEvent: Event? = null
        for (event in execution) {
            if (event.label is InitializationLabel)
                initEvent = event
            if (event.label !is MemoryAccessLabel)
                continue
            if (event.label.isRead && event.label.isResponse) {
                readsMap.computeIfAbsent(event.label.location) { arrayListOf() }.apply {
                    add(event)
                }
            }
            if (event.label.isWrite) {
                writesMap.computeIfAbsent(event.label.location) { arrayListOf() }.apply {
                    add(event)
                }
            }
        }
        for ((memId, writes) in writesMap) {
            writes.add(initEvent!!)
            relations[memId] = RelationMatrix(writes, buildIndexer(writes)) { x, y ->
                causalityOrder.lessThan(x, y)
            }
        }
    }

    fun saturate(): ReleaseAcquireConsistencyViolation? {
        for ((memId, relation) in relations) {
            val reads = readsMap[memId] ?: continue
            val writes = writesMap[memId] ?: continue
            var changed = false
            readLoop@ for (read in reads) {
                val readFrom = read.readsFrom
                writeLoop@ for (write in writes) {
                    if (write != readFrom && causalityOrder.lessThan(write, read) && !relation[write, readFrom]) {
                        relation[write, readFrom] = true
                        changed = true
                    }
                }
            }
            if (changed) {
                relation.transitiveClosure()
                if (!relation.isIrreflexive())
                    return ReleaseAcquireConsistencyViolation()
            }
        }
        return null
    }

    override fun invoke(x: Event, y: Event): Boolean {
        // TODO: handle InitializationLabel?
        return if (x.label is WriteAccessLabel && y.label is WriteAccessLabel &&
            x.label.location == y.label.location) {
            relations[x.label.location]?.get(x, y) ?: false
        } else false
    }

    fun isIrreflexive(): Boolean =
        relations.all { (_, relation) -> relation.isIrreflexive() }

    private fun buildIndexer(_events: ArrayList<Event>) = object : Indexer<Event> {

        val events: SortedList<Event> = SortedArrayList(_events.apply { sort() })

        override fun get(i: Int): Event = events[i]

        override fun index(x: Event): Int = events.indexOf(x)

    }

}