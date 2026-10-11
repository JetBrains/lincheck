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
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.*
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.util.MemoryOrdering
import org.jetbrains.lincheck.util.MemoryOrdering.*
import org.jetbrains.lincheck.util.Relation
import org.junit.Assert.*
import org.junit.Test

class SequentialConsistencyMixedAccessOrderTest {

    private val x = StaticFieldMemoryLocation(javaClass.name, "x", Types.INT_TYPE)
    private val y = StaticFieldMemoryLocation(javaClass.name, "y", Types.INT_TYPE)
    private val z = StaticFieldMemoryLocation(javaClass.name, "z", Types.INT_TYPE)

    @Test
    fun volatileWritesFollowCoherence() {
        val events = Events()
        val first = events.write(1, x)
        val second = events.write(2, x)
        val order = order(first to second)

        assertTrue(order(first, second))
        assertFalse(order(second, first))
        assertFalse(order(first, first))
    }

    @Test
    fun volatileReadPrecedesWritesAfterItsSource() {
        val events = Events()
        val write = events.write(1, x)
        val read = events.read(2, x, events.root)
        val order = order(events.root to write)

        assertTrue(order(read, write))
        assertFalse(order(write, read))
        assertFalse(order(read, read))
    }

    @Test
    fun coherenceDoesNotExtendThroughReadsFrom() {
        val events = Events()
        val first = events.write(1, x)
        val second = events.write(2, x)
        val volatileRead = events.read(3, x, second)
        val plainRead = events.read(4, x, second, PLAIN)
        val order = order(first to second)

        assertTrue(order(first, second))
        assertFalse(order(first, volatileRead))
        assertFalse(order(first, plainRead))
    }

    @Test
    fun programOrderConnectsDifferentLocations() {
        val events = Events()
        val write = events.write(1, x)
        val read = events.read(1, y, events.root)
        val order = order()

        assertTrue(order(write, read))
        assertFalse(order(read, write))
    }

    @Test
    fun happensBeforeConnectsTheSameLocationAcrossThreads() {
        val events = Events()
        val write = events.write(1, x)
        val read = events.read(2, x, write)
        val order = order()

        assertTrue(order(write, read))
        assertFalse(order(read, write))
    }

    @Test
    fun releaseAcquireCommunicationOrdersSurroundingVolatileAccesses() {
        val events = Events()
        val first = events.write(1, x)
        val release = events.write(1, y, RELEASE)
        events.read(2, y, release, ACQUIRE)
        val last = events.write(2, z)
        val order = order()

        assertTrue(order(first, last))
        assertFalse(order(last, first))
    }

    @Test
    fun crossLocationHappensBeforeNeedsProgramOrderOnBothSides() {
        val events = Events()
        val first = events.write(1, x)
        val release = events.write(1, y)
        val acquire = events.read(2, y, release)
        val last = events.write(2, z)
        val order = order()

        assertTrue(happensBeforeOrder(first, acquire))
        assertTrue(happensBeforeOrder(release, last))
        assertFalse(order(first, acquire))
        assertFalse(order(release, last))
        assertTrue(order(first, last))
    }

    @Test
    fun weakerMemoryAccessesAreNotEndpoints() {
        for ((writeOrdering, readOrdering) in listOf(PLAIN to PLAIN, OPAQUE to OPAQUE, RELEASE to ACQUIRE)) {
            val events = Events()
            val volatileWrite = events.write(1, x)
            val weakWrite = events.write(1, y, writeOrdering)
            val weakRead = events.read(1, x, volatileWrite, readOrdering)
            val volatileRead = events.read(1, y, weakWrite)
            val order = order()

            assertTrue(order(volatileWrite, volatileRead))
            assertFalse(order(volatileWrite, weakWrite))
            assertFalse(order(weakWrite, volatileRead))
            assertFalse(order(volatileWrite, weakRead))
            assertFalse(order(weakRead, volatileRead))
        }
    }

    @Test
    fun fencesAndReadRequestsAreNotEndpoints() {
        val events = Events()
        val write = events.write(1, x)
        val fence = events.fence(1, VOLATILE)
        val read = events.read(1, y, events.root)
        val request = read.request!!
        val order = order()

        assertTrue(order(write, read))
        assertFalse(order(write, fence))
        assertFalse(order(fence, read))
        assertFalse(order(write, request))
        assertFalse(order(request, read))
    }

    @Test
    fun releaseAcquireStoreBufferingIsConsistent() {
        val events = Events()
        events.write(1, x, RELEASE)
        events.read(1, y, events.root, ACQUIRE)
        events.write(2, y, RELEASE)
        events.read(2, x, events.root, ACQUIRE)

        assertNull(JmmConsistencyChecker().check(events.execution))
        assertNull(events.execution.checkConsistency())
    }

    @Test
    fun volatileStoreBufferingIsRecheckedAfterAppendingAndResetting() {
        val events = Events()
        val writeX = events.write(1, x)
        events.read(1, y, events.root)
        val frontier = events.execution.toFrontier()
        assertNull(events.execution.checkConsistency())

        events.write(2, y)
        events.read(2, x, events.root)
        assertTrue(events.execution.checkConsistency() is CoherenceViolation)
        assertTrue(JmmConsistencyChecker().check(events.execution) is CoherenceViolation)

        events.execution.reset(frontier)
        assertNull(events.execution.checkConsistency())
        assertNull(JmmConsistencyChecker().check(events.execution))

        events.write(2, y)
        events.read(2, x, writeX)
        assertNull(events.execution.checkConsistency())
        assertNull(JmmConsistencyChecker().check(events.execution))
    }

    private fun order(vararg coherenceEdges: Pair<AtomicThreadEvent, AtomicThreadEvent>) =
        SequentialConsistencyMixedAccessOrder(Relation { first, second ->
            (first to second) in coherenceEdges
        })

    private class Events {
        val execution = MutableExtendedExecution(MemoryModel.JAM21)
        val root = AtomicThreadEventImpl(InitializationLabel(0, 0) { 0L }, parent = null)

        init {
            execution.registerThread(root.threadId)
            execution.add(root)
            execution.reset(execution.toFrontier())
        }

        fun write(
            thread: Int,
            location: MemoryLocation,
            ordering: MemoryOrdering = VOLATILE,
        ): AtomicThreadEvent = append(thread, WriteAccessLabel(location, 1L, memoryOrdering = ordering))

        fun read(
            thread: Int,
            location: MemoryLocation,
            source: AtomicThreadEvent,
            ordering: MemoryOrdering = VOLATILE,
        ): AtomicThreadEvent {
            append(thread, ReadAccessLabel(LabelKind.Request, location, 0L, memoryOrdering = ordering))
            val value = (source.label as? WriteAccessLabel)?.value ?: 0L
            return append(
                thread,
                ReadAccessLabel(LabelKind.Response, location, value, memoryOrdering = ordering),
                senders = listOf(source),
            )
        }

        fun fence(thread: Int, ordering: MemoryOrdering): AtomicThreadEvent =
            append(thread, FenceLabel(ordering))

        private fun append(
            thread: Int,
            label: EventLabel,
            senders: List<AtomicThreadEvent> = emptyList(),
        ): AtomicThreadEvent {
            execution.registerThread(thread)
            if (execution.lastEvent(thread) == null) {
                val fork = AtomicThreadEventImpl(
                    ThreadForkLabel(setOf(thread)),
                    parent = execution.lastEvent(root.threadId),
                ).also(execution::add)
                execution.add(AtomicThreadEventImpl(
                    ThreadStartLabel(LabelKind.Receive, thread),
                    parent = null,
                    senders = listOf(fork),
                    dependencies = listOf(fork),
                ))
            }
            return AtomicThreadEventImpl(
                label,
                parent = execution.lastEvent(thread),
                senders = senders,
                allocation = root.takeIf { label is MemoryAccessLabel },
                dependencies = listOf(root) + senders,
            ).also(execution::add)
        }
    }
}
