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

import org.jetbrains.kotlinx.lincheck.ensure
import org.jetbrains.kotlinx.lincheck.strategy.managed.Remapping
import org.jetbrains.kotlinx.lincheck.unreachable
import org.jetbrains.kotlinx.lincheck.utils.*


/**
 * Execution represents a set of events belonging to single program's execution.
 */
interface Execution : Collection<Event> {
    val threadMap: ThreadMap<SortedList<Event>>

    operator fun get(tid: ThreadID): SortedList<Event>? =
        threadMap[tid]

    override fun contains(element: Event): Boolean =
        get(element.threadId, element.threadPosition) == element

    override fun containsAll(elements: Collection<Event>): Boolean =
        elements.all { contains(it) }

    override fun iterator(): Iterator<Event> =
        threadIDs.map { get(it)!! }.asSequence().flatten().iterator()

    fun executionOrderSortedList(): List<Event> =
        this.sorted()

}

interface MutableExecution : Execution {
    fun add(event: Event)
    fun cut(tid: ThreadID, pos: Int)
}

val Execution.threadIDs: Set<ThreadID>
    get() = threadMap.keys

val Execution.maxThreadID: ThreadID
    get() = threadIDs.maxOrNull() ?: -1

fun Execution.getThreadSize(tid: ThreadID): Int =
    get(tid)?.size ?: 0

fun Execution.lastPosition(tid: ThreadID): Int =
    getThreadSize(tid) - 1

fun Execution.firstEvent(tid: ThreadID): Event? =
    get(tid)?.firstOrNull()

fun Execution.lastEvent(tid: ThreadID): Event? =
    get(tid)?.lastOrNull()

operator fun Execution.get(tid: ThreadID, pos: Int): Event? =
    get(tid)?.getOrNull(pos)

fun Execution.nextEvent(event: Event): Event? =
    get(event.threadId)?.let { events ->
        require(events[event.threadPosition] == event)
        events.getOrNull(event.threadPosition + 1)
    }

fun MutableExecution.cut(event: Event) =
    cut(event.threadId, event.threadPosition)

fun MutableExecution.cutNext(event: Event) =
    cut(event.threadId, 1 + event.threadPosition)

fun Execution(nThreads: Int): Execution =
    MutableExecution(nThreads)

fun MutableExecution(nThreads: Int): MutableExecution =
    ExecutionImpl(ArrayMap(*(0 until nThreads)
        .map { (it to sortedArrayListOf<Event>()) }
        .toTypedArray()
    ))

fun executionOf(vararg pairs: Pair<ThreadID, List<Event>>): Execution =
    mutableExecutionOf(*pairs)

fun mutableExecutionOf(vararg pairs: Pair<ThreadID, List<Event>>): MutableExecution =
    ExecutionImpl(ArrayMap(*pairs
        .map { (tid, events) -> (tid to SortedArrayList(events)) }
        .toTypedArray()
    ))

private class ExecutionImpl(
    override val threadMap: ArrayMap<SortedMutableList<Event>>
) : MutableExecution {

    override var size: Int = threadMap.values.sumOf { it.size }
        private set

    override fun isEmpty(): Boolean =
        (size > 0)

    override fun get(tid: ThreadID): SortedMutableList<Event>? =
        threadMap[tid]

    override fun add(event: Event) {
        ++size
        threadMap[event.threadId]!!
            .ensure { event.parent == it.lastOrNull() }
            .also { it.add(event) }
    }

    override fun cut(tid: ThreadID, pos: Int) {
        val threadEvents = get(tid) ?: return
        size -= (threadEvents.size - pos)
        threadEvents.cut(pos)
    }

    override fun equals(other: Any?): Boolean =
        (other is ExecutionImpl) && (size == other.size) && (threadMap == other.threadMap)

    override fun hashCode(): Int =
       threadMap.hashCode()

    override fun toString(): String = buildString {
        appendLine("<======== Execution Graph @${hashCode()} ========>")
        threadIDs.toList().sorted().forEach { tid ->
            val events = threadMap[tid] ?: return@forEach
            appendLine("[-------- Thread #${tid} --------]")
            for (event in events) {
                appendLine("$event")
                if (event.dependencies.isNotEmpty()) {
                    appendLine("    dependencies: ${event.dependencies.joinToString {
                        "#${it.id}: [${it.threadId}, ${it.threadPosition}]"
                    }}")
                }
            }
        }
    }

}

fun Execution.toFrontier(): ExecutionFrontier =
    toMutableFrontier()

fun Execution.toMutableFrontier(): MutableExecutionFrontier =
    threadIDs.map { tid ->
        tid to get(tid)?.lastOrNull()
    }.let {
        mutableExecutionFrontierOf(*it.toTypedArray())
    }

fun Execution.buildIndexer() = object : Indexer<Event> {

    private val events = executionOrderSortedList()

    private val eventIndices = threadMap.mapValues { (_, threadEvents) ->
        List(threadEvents.size) { pos ->
            events.indexOf(threadEvents[pos]).ensure { it >= 0 }
        }
    }

    override fun get(i: Int): Event {
        return events[i]
    }

    override fun index(x: Event): Int {
        return eventIndices[x.threadId]!![x.threadPosition]
    }

}

fun Execution.computeVectorClock(event: Event, relation: Relation<Event>): VectorClock {
    check(this is ExecutionImpl)
    val clock = MutableVectorClock(threadMap.capacity)
    for (i in 0 until threadMap.capacity) {
        val threadEvents = get(i) ?: continue
        clock[i] = (threadEvents.binarySearch { !relation(it, event) } - 1)
            .coerceAtLeast(-1)
    }
    return clock
}

// TODO: rename?
fun Execution.fixupDependencies(): Remapping {
    val remapping = Remapping()
    // TODO: refactor, simplify & unify cases
    for (event in executionOrderSortedList()) {
        if (!(event.label is MemoryAccessLabel || event.label is MutexLabel))
            continue
        // TODO: unify cases
        if (event.label.isRequest || event.label.isSend) {
            val obj = when (event.label) {
                is MemoryAccessLabel -> event.label.location.obj
                is MutexLabel -> event.label.mutex.unwrap()
                else -> unreachable()
            }
            val allocEvent = event.dependencies.firstOrNull { it.label is ObjectAllocationLabel }
                // TODO: add check for `external` objects allocated by `InitializationLabel`
                ?: continue
            remapping[obj] = (allocEvent.label as ObjectAllocationLabel).obj.unwrap()
            event.label.remap(remapping)
        }
        if (event.label.isResponse) {
            val req = event.parent!!
            val objFrom = when (req.label) {
                is MemoryAccessLabel -> req.label.location.obj
                is MutexLabel -> req.label.mutex.unwrap()
                else -> unreachable()
            }
            val syncFrom = event.syncFrom
            val objTo = when (syncFrom.label) {
                is MemoryAccessLabel -> syncFrom.label.location.obj
                is MutexLabel -> syncFrom.label.mutex.unwrap()
                else -> null
            }
            if (objTo != null) {
                remapping[objFrom] = objTo
                req.label.remap(remapping)
            }
            event.label.replay(event.recalculateResponseLabel(), remapping)
        }
    }
    return remapping
}

typealias ExecutionCounter = IntArray

abstract class ExecutionRelation(
    val execution: Execution,
    val respectsProgramOrder: Boolean = true,
) : Relation<Event> {

    val indexer = execution.buildIndexer()

    fun buildExternalCovering() = object : Covering<Event> {

        init {
            require(respectsProgramOrder)
        }

        val relation = this@ExecutionRelation

        private val nThreads = 1 + execution.maxThreadID

        val covering: List<List<Event>> = execution.indices.map { index ->
            val event = indexer[index]
            val clock = execution.computeVectorClock(event, relation)
            (0 until nThreads).mapNotNull { tid ->
                if (tid != event.threadId && clock[tid] != -1)
                    execution[tid, clock[tid]]
                else null
            }
        }

        override fun invoke(x: Event): List<Event> =
            covering[indexer.index(x)]

    }

}

fun executionRelation(
    execution: Execution,
    respectsProgramOrder: Boolean = true,
    relation: Relation<Event>
) = object : ExecutionRelation(execution, respectsProgramOrder) {

    override fun invoke(x: Event, y: Event): Boolean = relation(x, y)

}