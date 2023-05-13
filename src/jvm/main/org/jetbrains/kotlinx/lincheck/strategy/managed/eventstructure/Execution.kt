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

import org.jetbrains.kotlinx.lincheck.unreachable
import org.jetbrains.kotlinx.lincheck.ensure

/**
 * Execution represents a set of events belonging to single program's execution.
 */
open class Execution(
    threadEvents: Map<Int, List<Event>> = emptyMap(),
) : Collection<Event> {
    /**
     * Execution is encoded as a mapping `ThreadID -> List<Event>`
     * from thread id to a list of events belonging to this thread ordered by program-order.
     * We also assume that program order is compatible with execution order,
     * and thus events within the same thread are also ordered by execution order.
     *
     * TODO: use array instead of map?
     */
    protected val threadsEvents: MutableMap<Int, SortedArrayList<Event>> =
        threadEvents.map { (threadId, events) -> threadId to SortedArrayList(events) }.toMap().toMutableMap()

    val threads: Set<Int>
        get() = threadsEvents.keys

    override val size: Int
        get() = threadsEvents.values.sumOf { it.size }

    val maxThreadId: Int
        get() = threads.maxOrNull()?.let { 1 + it } ?: 0

    val rootEvent: Event by lazy {
        this.first { it.label is InitializationLabel }
    }

    override fun isEmpty(): Boolean =
        threadsEvents.isEmpty()

    fun getThreadSize(iThread: Int): Int =
        threadsEvents[iThread]?.size ?: 0

    fun lastPosition(iThread: Int): Int =
        getThreadSize(iThread) - 1

    fun firstEvent(iThread: Int): Event? =
        threadsEvents[iThread]?.firstOrNull()

    fun lastEvent(iThread: Int): Event? =
        threadsEvents[iThread]?.lastOrNull()

    operator fun get(iThread: Int): SortedArrayList<Event>? =
        threadsEvents[iThread]

    operator fun get(iThread: Int, Position: Int): Event? =
        threadsEvents[iThread]?.getOrNull(Position)

    fun nextEvent(event: Event): Event? =
        threadsEvents[event.threadId]?.let { events ->
            require(events[event.threadPosition] == event)
            events.getOrNull(event.threadPosition + 1)
        }

    fun toFrontier(): ExecutionFrontier = ExecutionFrontier(
        threadsEvents.mapValues { (_, events) -> events.last() }
    )

    override operator fun contains(element: Event): Boolean =
        threadsEvents[element.threadId]
            ?.let { events -> events[element.threadPosition] == element }
            ?: false

    override fun containsAll(elements: Collection<Event>): Boolean =
        elements.all { contains(it) }

    override fun equals(other: Any?): Boolean {
        if (other !is Execution) return false
        return threadsEvents == other.threadsEvents
    }

    override fun hashCode(): Int =
        threadsEvents.hashCode()

    override fun toString(): String = buildString {
        appendLine("<======== Execution Graph @${hashCode()} ========>")
        threads.toList().sorted().forEach { tid ->
            val events = threadsEvents[tid] ?: return@forEach
            appendLine("[-------- Thread #${tid} --------]")
            for (event in events) {
                append("$event")
                if (event.dependencies.isNotEmpty()) {
                    appendLine()
                    append("    dependencies: ${event.dependencies.joinToString { 
                        "#${it.id}: [${it.threadId}, ${it.threadPosition}]" 
                    }}")
                }
                appendLine()
            }
        }
    }

    infix fun equivalent(other: Execution): Boolean =
        this.all { it equivalent (other[it.threadId, it.threadPosition] ?: return false) }

    private infix fun Event.equivalent(other: Event): Boolean =
        threadId == other.threadId &&
        threadPosition == other.threadPosition &&
        // TODO: check for label up to replaying
        dependencies.size == other.dependencies.size &&
        dependencies.all { e1 -> other.dependencies.any { e2 ->
            e1 equivalent e2
        }}

    override fun iterator() = object : Iterator<Event> {

        private val counter = ExecutionCounter(maxThreadId) { 0 }
        private var nextEvent: Event? = null

        init {
            setNextEvent()
        }

        override fun hasNext(): Boolean =
            nextEvent != null

        override fun next(): Event =
            nextEvent!!.also { setNextEvent() }

        private fun setNextEvent() {
            if (nextEvent != null) {
                counter[nextEvent!!.threadId]++
            }
            nextEvent = null
            for (thread in counter.indices) {
                val pos = counter[thread]
                if (pos >= (this@Execution[thread]?.size ?: 0))
                    continue
                val event = this@Execution[thread, pos]!!
                if (nextEvent == null || event.id < nextEvent!!.id)
                    nextEvent = event
            }
        }

    }

    fun buildIndexer() = object : Indexer<Event> {

        private val threadOffsets: IntArray =
            IntArray(maxThreadId).apply {
                var offset = 0
                for (i in indices) {
                    this[i] = offset
                    offset += getThreadSize(i)
                }
            }

        private val events: Array<Event> =
            Array(size) { i ->
                for (threadId in threadOffsets.indices) {
                    if (i < threadOffsets[threadId] + getThreadSize(threadId))
                        return@Array this@Execution[threadId, i - threadOffsets[threadId]]!!
                }
                unreachable()
            }

        override fun index(x: Event): Int {
            // require(x in this@Execution)
            return threadOffsets[x.threadId] + x.threadPosition
        }

        override fun get(i: Int): Event {
            require(i < events.size)
            return events[i]
        }

    }

}

class MutableExecution(
    threadEvents: Map<Int, List<Event>> = emptyMap(),
) : Execution(threadEvents) {

    fun addEvent(event: Event) {
        val threadEvents = threadsEvents.getOrPut(event.threadId) { sortedArrayListOf() }
        check(event.parent == threadEvents.lastOrNull())
        threadEvents.add(event)
    }

    fun removeLastEvent(event: Event) {
        val threadEvents = threadsEvents[event.threadId]
        check(event == threadEvents?.lastOrNull())
        threadEvents?.removeLast()
    }

    fun removeDanglingRequestEvents() {
        for (threadId in threads) {
            val lastEvent = get(threadId)?.lastOrNull() ?: continue
            if (lastEvent.label.isRequest && !lastEvent.label.isBlocking) {
                lastEvent.parent?.label?.ensure { !it.isRequest }
                removeLastEvent(lastEvent)
            }
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
            val counter = IntArray(execution.maxThreadId) { -1 }
            for (other in execution) {
                if (relation(other, event) && other.threadPosition > counter[other.threadId]) {
                    counter[other.threadId] = other.threadPosition
                }
            }
            (0 until execution.maxThreadId).mapNotNull { threadId ->
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