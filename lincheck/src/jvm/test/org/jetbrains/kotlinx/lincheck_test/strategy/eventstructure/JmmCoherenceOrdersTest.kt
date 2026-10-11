/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure

import org.jetbrains.kotlinx.lincheck.strategy.managed.MemoryLocation
import org.jetbrains.kotlinx.lincheck.strategy.managed.StaticFieldMemoryLocation
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.ReadModifyWriteOrder
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.WritesBeforeOrder
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.util.MemoryOrdering
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JmmCoherenceOrdersTest {

    @Test
    fun initializationPrecedesWritesWithoutHappensBefore() {
        val events = Events(forkThreads = false)
        val write = events.write(1)

        assertFalse(happensBeforeOrder(events.initialization, write))
        assertTrue(events.writesBefore()(events.initialization, write))
    }

    @Test
    fun messagePassingForbidsStaleInitializationWithoutInitializationHappensBefore() {
        val events = Events(forkThreads = false)
        val flag = StaticFieldMemoryLocation(javaClass.name, "flag", Types.INT_TYPE)
        val write = events.write(1)
        val publication = events.write(1, location = flag, memoryOrdering = MemoryOrdering.VOLATILE)
        events.read(2, publication, memoryOrdering = MemoryOrdering.VOLATILE)
        val staleRead = events.read(2, events.initialization)

        assertFalse(happensBeforeOrder(events.initialization, write))
        assertFalse(happensBeforeOrder(events.initialization, staleRead))
        assertTrue(happensBeforeOrder(write, staleRead))
        assertFalse(events.writesBefore().isConsistent())
    }

    @Test
    fun readPredecessorOrdersItsSourceBeforeALaterWrite() {
        val events = Events()
        val source = events.write(1)
        events.read(2, source)
        val laterWrite = events.write(2)

        assertFalse(happensBeforeOrder(source, laterWrite))
        assertTrue(events.writesBefore()(source, laterWrite))
    }

    @Test
    fun readPredecessorOrdersItsSourceBeforeALaterReadsSource() {
        val events = Events()
        val firstSource = events.write(1)
        val secondSource = events.write(2)
        events.read(3, firstSource)
        events.read(3, secondSource)

        assertFalse(happensBeforeOrder(firstSource, secondSource))
        assertTrue(events.writesBefore()(firstSource, secondSource))
    }

    @Test
    fun readCoherenceIsCheckedWhenWritesAreTotallyOrdered() {
        val events = Events()
        val firstWrite = events.write(1)
        val secondWrite = events.write(1)
        events.read(2, secondWrite)
        events.read(2, firstWrite)

        val index = events.memoryAccessIndex()
        assertTrue(index.isWriteWriteRaceFree(events.location))
        assertFalse(events.writesBefore().isConsistent())
    }

    @Test
    fun independentReadModifyWriteChainsCanBeReordered() {
        val events = Events()
        val firstWrite = events.write(1)
        val firstUpdate = events.update(2, firstWrite)
        val secondWrite = events.write(3)
        val secondUpdate = events.update(4, secondWrite)
        val order = ReadModifyWriteOrder(events.execution()).apply { compute() }

        assertTrue(order.respectful(listOf(events.initialization, firstWrite, firstUpdate, secondWrite, secondUpdate)))
        assertTrue(order.respectful(listOf(events.initialization, secondWrite, secondUpdate, firstWrite, firstUpdate)))
        assertFalse(order.respectful(listOf(events.initialization, firstWrite, secondWrite, firstUpdate, secondUpdate)))
        assertFalse(order.respectful(listOf(events.initialization, firstUpdate, firstWrite, secondWrite, secondUpdate)))
    }

    private class Events(private val forkThreads: Boolean = true) {
        val location = StaticFieldMemoryLocation(JmmCoherenceOrdersTest::class.java.name, "value", Types.INT_TYPE)
        val initialization = AtomicThreadEventImpl(InitializationLabel(0, 0) { 0L }, parent = null)
        private val events = mutableListOf<AtomicThreadEvent>(initialization)
        private val lastEvents = mutableMapOf<Int, AtomicThreadEvent>(0 to initialization)

        fun execution(): Execution<AtomicThreadEvent> =
            executionOf(*events.groupBy { it.threadId }.map { it.key to it.value }.toTypedArray())

        fun memoryAccessIndex(): AtomicMemoryAccessEventIndex =
            MutableAtomicMemoryAccessEventIndex().apply { index(execution()) }

        fun writesBefore(): WritesBeforeOrder {
            val execution = execution()
            val rmwOrder = ReadModifyWriteOrder(execution).apply { compute() }
            return WritesBeforeOrder(execution, memoryAccessIndex(), rmwOrder, happensBeforeOrder,
                includeReadPredecessors = true
            ).apply { initialize(); compute() }
        }

        fun write(
            threadId: Int,
            descriptor: ReadModifyWriteDescriptor? = null,
            location: MemoryLocation = this.location,
            memoryOrdering: MemoryOrdering = MemoryOrdering.PLAIN,
        ): AtomicThreadEvent =
            append(AtomicThreadEventImpl(
                WriteAccessLabel(location, events.size.toLong(), descriptor, memoryOrdering = memoryOrdering),
                parent = last(threadId),
                allocation = initialization,
            ))

        fun read(
            threadId: Int,
            source: AtomicThreadEvent,
            descriptor: ReadModifyWriteDescriptor? = null,
            location: MemoryLocation = (source.label as? MemoryAccessLabel)?.location ?: this.location,
            memoryOrdering: MemoryOrdering = MemoryOrdering.PLAIN,
        ): AtomicThreadEvent {
            val request = append(AtomicThreadEventImpl(
                ReadAccessLabel(LabelKind.Request, location, 0L, descriptor, memoryOrdering = memoryOrdering),
                parent = last(threadId),
                allocation = initialization,
            ))
            return append(AtomicThreadEventImpl(
                ReadAccessLabel(LabelKind.Response, location, (source.label as? WriteAccessLabel)?.writeValue ?: 0L,
                    descriptor, memoryOrdering = memoryOrdering),
                parent = request,
                senders = listOf(source),
                allocation = initialization,
                dependencies = listOf(source),
            ))
        }

        fun update(threadId: Int, source: AtomicThreadEvent): AtomicThreadEvent {
            val descriptor = ReadModifyWriteDescriptor.GetAndSetDescriptor(events.size.toLong())
            read(threadId, source, descriptor)
            return write(threadId, descriptor)
        }

        private fun last(threadId: Int): AtomicThreadEvent {
            lastEvents[threadId]?.let { return it }
            val sender = if (forkThreads) {
                append(AtomicThreadEventImpl(ThreadForkLabel(setOf(threadId)), parent = lastEvents[0]))
            } else initialization
            return append(AtomicThreadEventImpl(
                ThreadStartLabel(LabelKind.Receive, threadId),
                parent = null,
                senders = listOf(sender),
                dependencies = listOf(sender),
            ))
        }

        private fun append(event: AtomicThreadEvent): AtomicThreadEvent {
            events.add(event)
            lastEvents[event.threadId] = event
            return event
        }
    }
}
