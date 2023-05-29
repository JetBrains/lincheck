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
import org.jetbrains.kotlinx.lincheck.utils.*


/**
 * Execution represents a set of events belonging to single program's execution.
 */
interface Execution : Collection<Event> {
    val threadMap: ThreadMap<SortedList<Event>>

    operator fun get(tid: ThreadID): SortedList<Event>? =
        threadMap[tid]

    override fun contains(element: Event): Boolean =
        get(element.threadId)?.let { events ->
            events[element.threadPosition] == element
        } ?: false

    override fun containsAll(elements: Collection<Event>): Boolean =
        elements.all { contains(it) }

    override fun iterator(): Iterator<Event> =
        threadIDs.map { get(it)!! }.asSequence().flatten().iterator()

    fun executionOrderSortedList(): List<Event> =
        this.sorted()

}

interface MutableExecution : Execution {
    fun addEvent(event: Event)
    fun removeLastEvent(event: Event)
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
    ExecutionImpl(nThreads)

fun MutableExecution(nThreads: Int): MutableExecution =
    ExecutionImpl(nThreads)

fun executionOf(vararg pairs: Pair<ThreadID, List<Event>>): Execution =
    ExecutionImpl(*pairs)

fun mutableExecutionOf(vararg pairs: Pair<ThreadID, List<Event>>): MutableExecution =
    ExecutionImpl(*pairs)

private class ExecutionImpl(val nThreads: Int) : MutableExecution {

    override var size: Int = 0
        private set

    override val threadMap = ArrayMap<SortedMutableList<Event>>(nThreads) {
        sortedArrayListOf()
    }

    constructor(vararg pairs: Pair<ThreadID, List<Event>>)
            : this(pairs.maxOfOrNull { (tid, _) -> tid } ?: 0) {
        require(pairs.all { (tid, _) -> tid >= 0 })
        if (nThreads == 0)
            return
        pairs.forEach { (tid, threadEvents) ->
            size += threadEvents.size
            threadMap[tid] = SortedArrayList(threadEvents)
        }
    }

    override fun isEmpty(): Boolean =
        (size > 0)

    override fun get(tid: ThreadID): SortedMutableList<Event>? =
        threadMap[tid]

    override fun addEvent(event: Event) {
        ++size
        threadMap[event.threadId]!!
            .ensure { event.parent == it.lastOrNull() }
            .also { it.add(event) }
    }

    override fun removeLastEvent(event: Event) {
        --size
        threadMap[event.threadId]!!
            .ensure { event == it.lastOrNull() }
            .removeLast()
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
        tid to get(tid)!!.last()
    }.let {
        mutableExecutionFrontierOf(*it.toTypedArray())
    }

fun Execution.buildIndexer() = object : Indexer<Event> {

    private val events = executionOrderSortedList()

    private val eventIndices = ArrayMap(1 + maxThreadID) { tid ->
        val threadEvents = threadMap[tid] ?: listOf()
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

fun MutableExecution.removeDanglingRequestEvents() {
    for (threadId in threadIDs) {
        val lastEvent = get(threadId)?.lastOrNull() ?: continue
        if (lastEvent.label.isRequest && !lastEvent.label.isBlocking) {
            lastEvent.parent?.label?.ensure { !it.isRequest }
            removeLastEvent(lastEvent)
        }
    }
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

        val covering: List<List<Event>> = execution.indices.map { index ->
            val event = indexer[index]
            val nThreads = 1 + execution.maxThreadID
            val counter = IntArray(nThreads) { -1 }
            for (other in execution) {
                if (relation(other, event) && other.threadPosition > counter[other.threadId]) {
                    counter[other.threadId] = other.threadPosition
                }
            }
            (0 until nThreads).mapNotNull { threadId ->
                if (threadId != event.threadId && counter[threadId] != -1)
                    execution[threadId, counter[threadId]]
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