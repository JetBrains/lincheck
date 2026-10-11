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

import org.jetbrains.kotlinx.lincheck.strategy.managed.StaticFieldMemoryLocation
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.*
import org.jetbrains.lincheck.util.CachedRelation
import org.jetbrains.lincheck.util.MemoryOrdering
import org.jetbrains.lincheck.util.Relation
import org.jetbrains.lincheck.descriptors.Types
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ConsistencyCheckerTest(private val memoryModel: MemoryModel) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun memoryModels() = listOf(MemoryModel.SequentialConsistency, MemoryModel.ReleaseAcquire)
    }

    @Test
    fun stateDistinguishesIncrementalAndFullChecks() {
        val execution = MutableExtendedExecution(memoryModel)
        val checker = checker(execution)
        assertSame(ConsistencyState.Unknown, checker.state)
        assertNull(checker.check())
        assertSame(ConsistencyState.Consistent, checker.state)

        val initial = AtomicThreadEventImpl(InitializationLabel(0, 1) { 0L }, parent = null)
        execution.registerThread(0)
        execution.add(initial)
        assertSame(ConsistencyState.PartlyConsistent, checker.check(initial))
        assertNull(checker.check())
        assertSame(ConsistencyState.Consistent, checker.state)

        execution.reset(ExecutionFrontier())
        assertSame(ConsistencyState.Unknown, checker.reset(execution))
        assertNull(checker.check())
        assertSame(ConsistencyState.Consistent, checker.state)
    }

    @Test
    fun fullCheckDetectsStaleReadAfterSuccessfulIncrementalChecks() {
        val graph = Graph()
        val first = graph.write(1, graph.x, 1)
        graph.write(1, graph.x, 2)
        val prefix = graph.frontier()
        assertNull(graph.checker.check())

        graph.read(1, graph.x, first)
        assertSame(ConsistencyState.PartlyConsistent, graph.checker.state)
        val inconsistency = graph.checker.check()
        assertNotNull(inconsistency)
        assertSame(inconsistency, graph.checker.check())
        graph.write(2, graph.y, 1)
        assertSame(inconsistency, graph.checker.state.inconsistency)

        graph.reset(prefix)
        assertSame(ConsistencyState.Unknown, graph.checker.state)
        assertNull(graph.checker.check())
        assertNull(graph.execution.checkConsistency())
        graph.write(1, graph.x, 3)
        assertSame(ConsistencyState.PartlyConsistent, graph.checker.state)
        assertNull(graph.checker.check())
    }

    @Test
    fun conflictingAtomicUpdatesAreDetectedBeforeFullCheckAndRecoverAfterReset() {
        val graph = Graph()
        graph.atomicUpdate(1, graph.initialX, 1)
        val prefix = graph.frontier()
        assertNull(graph.checker.check())
        val order = graph.execution.readModifyWriteOrder
        val chain = order[graph.x]!!.single().toList()
        repeat(2) { order.compute() }
        assertEquals(chain, order[graph.x]!!.single())

        graph.atomicUpdate(2, graph.initialX, 2)
        assertTrue(graph.checker.state.inconsistency is ReadModifyWriteAtomicityViolation)
        assertSame(graph.checker.state.inconsistency, graph.checker.check())

        graph.reset(prefix)
        assertNull(graph.checker.check())
        assertEquals(chain, order[graph.x]!!.single())
        graph.atomicUpdate(1, chain.last(), 3)
        assertNull(graph.checker.check())
        assertEquals(3, order[graph.x]!!.single().size)
    }

    @Test
    fun atomicityIsCheckedAfterResetBeforeTheNextFullCheck() {
        val graph = Graph()
        graph.atomicUpdate(1, graph.initialX, 1)
        graph.reset(graph.frontier())
        assertSame(ConsistencyState.Unknown, graph.checker.state)
        graph.atomicUpdate(2, graph.initialX, 2)
        assertTrue(graph.checker.state.inconsistency is ReadModifyWriteAtomicityViolation)
        assertTrue(graph.execution.inconsistency is ReadModifyWriteAtomicityViolation)
    }

    @Test
    fun writesBeforeIsCachedUntilAnAdditionOrReset() {
        val graph = Graph()
        graph.write(1, graph.x, 1)
        val second = graph.write(2, graph.x, 2)
        val prefix = graph.frontier()
        var evaluations = 0
        val order = WritesBeforeOrder(
            graph.execution.memoryAccessEventIndex,
            graph.execution.readModifyWriteOrder,
            Relation { x, y ->
                evaluations++
                happensBeforeOrder(x, y)
            },
        )
        order.compute()
        assertTrue(evaluations > 0)
        val cachedEvaluations = evaluations
        order.compute()
        assertEquals(cachedEvaluations, evaluations)

        val third = graph.write(2, graph.x, 3)
        order.onAdd(third)
        order.compute()
        assertTrue(evaluations > cachedEvaluations)
        assertTrue(order(second, third))
        val expandedEvaluations = evaluations
        order.compute()
        assertEquals(expandedEvaluations, evaluations)

        graph.reset(prefix)
        order.onReset(graph.execution)
        order.compute()
        assertTrue(evaluations > expandedEvaluations)
        val resetEvaluations = evaluations
        order.compute()
        assertEquals(resetEvaluations, evaluations)
    }

    @Test
    fun cachedRelationsFollowExecutionAdditionsAndReset() {
        val graph = Graph()
        graph.write(1, graph.x, 1)
        graph.write(2, graph.x, 2)
        val prefix = graph.frontier()
        val original = graph.relationsSnapshot()
        assertEquals(original, graph.relationsSnapshot())

        val third = graph.write(2, graph.x, 3)
        graph.read(1, graph.x, third)
        val expanded = graph.relationsSnapshot()
        assertNotEquals(original, expanded)
        assertEquals(expanded, graph.relationsSnapshot())
        assertNull(graph.checker.check())

        graph.reset(prefix)
        assertEquals(original, graph.relationsSnapshot())
        assertNull(graph.checker.check())
    }

    @Test
    fun resetCanSwitchToAnotherExecution() {
        val consistent = Graph()
        val inconsistent = Graph()
        val first = inconsistent.write(1, inconsistent.x, 1)
        inconsistent.write(1, inconsistent.x, 2)
        inconsistent.read(1, inconsistent.x, first)
        val checker = consistent.checker
        assertNull(checker.check())
        assertSame(ConsistencyState.Unknown, checker.reset(inconsistent.execution))
        assertNotNull(checker.check())
        assertSame(ConsistencyState.Unknown, checker.reset(consistent.execution))
        assertNull(checker.check())
    }

    @Test
    fun storeBufferingDistinguishesSequentialConsistencyFromReleaseAcquire() {
        val graph = Graph()
        graph.write(1, graph.x, 1)
        graph.write(2, graph.y, 1)
        graph.read(1, graph.y, graph.initialY)
        graph.read(2, graph.x, graph.initialX)
        assertSame(ConsistencyState.PartlyConsistent, graph.checker.state)
        if (memoryModel == MemoryModel.SequentialConsistency) {
            assertNotNull(graph.checker.check())
            assertNotNull(graph.execution.checkConsistency())
        } else {
            assertNull(graph.checker.check())
            assertNull(graph.execution.checkConsistency())
            assertTrue(graph.execution.executionOrder.computed)
            assertEquals(graph.execution.size, graph.execution.executionOrder.ordering.size)
        }
    }

    private fun checker(execution: MutableExtendedExecution):
        IncrementalConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution> = when (memoryModel) {
        MemoryModel.SequentialConsistency -> SequentialConsistencyChecker(execution)
        MemoryModel.ReleaseAcquire -> ReleaseAcquireConsistencyChecker(execution)
        MemoryModel.JAM21 -> error("Unsupported memory model")
    }

    private inner class Graph {
        val execution = MutableExtendedExecution(memoryModel)
        val checker = checker(execution)
        val x = StaticFieldMemoryLocation("ConsistencyCheckerTest", "x", Types.INT_TYPE)
        val y = StaticFieldMemoryLocation("ConsistencyCheckerTest", "y", Types.INT_TYPE)
        private val initial = add(AtomicThreadEventImpl(InitializationLabel(0, 1) { 0L }, parent = null))
        val initialX: AtomicThreadEvent
        val initialY: AtomicThreadEvent
        private val writeOrdering =
            if (memoryModel == MemoryModel.SequentialConsistency) MemoryOrdering.VOLATILE else MemoryOrdering.RELEASE
        private val readOrdering =
            if (memoryModel == MemoryModel.SequentialConsistency) MemoryOrdering.VOLATILE else MemoryOrdering.ACQUIRE

        init {
            add(AtomicThreadEventImpl(
                ThreadStartLabel(LabelKind.Receive, 1), parent = null,
                senders = listOf(initial), dependencies = listOf(initial),
            ))
            initialX = write(1, x, 0)
            initialY = write(1, y, 0)
            val fork = add(AtomicThreadEventImpl(ThreadForkLabel(setOf(2)), execution.lastEvent(1)))
            add(AtomicThreadEventImpl(
                ThreadStartLabel(LabelKind.Receive, 2), parent = null,
                senders = listOf(fork), dependencies = listOf(fork),
            ))
        }

        fun write(
            thread: Int,
            location: StaticFieldMemoryLocation,
            value: Long,
            descriptor: ReadModifyWriteDescriptor? = null,
        ): AtomicThreadEvent = add(AtomicThreadEventImpl(
            WriteAccessLabel(location, value, descriptor, memoryOrdering = writeOrdering),
            execution.lastEvent(thread), allocation = initial, dependencies = listOf(initial),
        ))

        fun read(
            thread: Int,
            location: StaticFieldMemoryLocation,
            from: AtomicThreadEvent,
            descriptor: ReadModifyWriteDescriptor? = null,
        ): AtomicThreadEvent {
            val request = add(AtomicThreadEventImpl(
                ReadAccessLabel(LabelKind.Request, location, 0L, descriptor, memoryOrdering = readOrdering),
                execution.lastEvent(thread), allocation = initial, dependencies = listOf(initial),
            ))
            val value = (from.label as? WriteAccessLabel)?.writeValue ?: 0L
            return add(AtomicThreadEventImpl(
                ReadAccessLabel(LabelKind.Response, location, value, descriptor, memoryOrdering = readOrdering),
                request, senders = listOf(from), allocation = initial, dependencies = listOf(initial, from),
            ))
        }

        fun atomicUpdate(thread: Int, from: AtomicThreadEvent, value: Long) {
            val descriptor = ReadModifyWriteDescriptor.GetAndSetDescriptor(value)
            read(thread, x, from, descriptor)
            write(thread, x, value, descriptor)
        }

        fun frontier(): ExecutionFrontier<AtomicThreadEvent> = executionFrontierOf(
            *execution.threadMap.map { (thread, events) -> thread to events.lastOrNull() }.toTypedArray(),
        )

        fun reset(frontier: ExecutionFrontier<AtomicThreadEvent>) {
            execution.reset(frontier)
            checker.reset(execution)
        }

        fun relationsSnapshot(): List<List<Pair<AtomicThreadEvent, AtomicThreadEvent>>> {
            val relations: List<CachedRelation<AtomicThreadEvent>> = listOf(
                execution.readModifyWriteOrder, execution.writesBeforeOrder, execution.coherenceOrder,
                execution.extendedCoherence, execution.memoryModelConsistencyOrder, execution.executionOrder,
            )
            return relations.map { relation ->
                relation.compute()
                execution.flatMap { x -> execution.filter { y -> relation(x, y) }.map { y -> x to y } }
            }
        }

        private fun add(event: AtomicThreadEvent): AtomicThreadEvent {
            execution.registerThread(event.threadId)
            execution.add(event)
            checker.check(event)
            return event
        }
    }
}
