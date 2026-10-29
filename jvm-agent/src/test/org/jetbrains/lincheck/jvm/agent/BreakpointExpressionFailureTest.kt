/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sun.nio.ch.lincheck.BreakpointStorage
import sun.nio.ch.lincheck.BreakpointStorage.CONDITION_FAILURE_LIMIT
import sun.nio.ch.lincheck.BreakpointStorage.EXPRESSION_SLOT_CONDITION
import sun.nio.ch.lincheck.BreakpointStorage.EXPRESSION_SLOT_WATCH
import sun.nio.ch.lincheck.Injections
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/** Per-hit expression failure reporting: once per (slot, failure kind), plus the condition circuit breaker. */
class BreakpointExpressionFailureTest {

    private data class Event(val id: Int, val slot: Int, val failureKind: String, val disabled: Boolean)

    private val events = mutableListOf<Event>()
    private val id = 4242

    /** An application exception whose rendering is application code that must never run. */
    private class HostileException : RuntimeException() {
        override val message: String get() = error("getMessage() must not be called")
        override fun toString(): String = error("toString() must not be called")
    }

    @Before
    fun setUp() {
        BreakpointStorage.clear()
        BreakpointStorage.setOnBreakpointExpressionEvaluationFailed { id, _, slot, failureKind, disabled ->
            synchronized(events) { events += Event(id, slot, failureKind, disabled) }
        }
        BreakpointStorage.registerBreakpoint(id, Int.MAX_VALUE, "payload")
    }

    @After
    fun tearDown() {
        BreakpointStorage.setOnBreakpointExpressionEvaluationFailed(null)
        BreakpointStorage.clear()
    }

    @Test
    fun `a repeated failure is reported once per slot and kind`() {
        repeat(5) { Injections.onBreakpointExpressionEvaluationFailed(NullPointerException(), id, EXPRESSION_SLOT_CONDITION) }
        repeat(5) { Injections.onBreakpointExpressionEvaluationFailed(ArithmeticException(), id, EXPRESSION_SLOT_CONDITION) }
        repeat(5) { Injections.onBreakpointExpressionEvaluationFailed(NullPointerException(), id, EXPRESSION_SLOT_WATCH) }

        assertEquals(
            listOf(
                Event(id, EXPRESSION_SLOT_CONDITION, "java.lang.NullPointerException", false),
                Event(id, EXPRESSION_SLOT_CONDITION, "java.lang.ArithmeticException", false),
                Event(id, EXPRESSION_SLOT_WATCH, "java.lang.NullPointerException", false),
            ),
            events,
        )
    }

    @Test
    fun `only the class name of the thrown object is read`() {
        Injections.onBreakpointExpressionEvaluationFailed(HostileException(), id, EXPRESSION_SLOT_CONDITION)

        assertEquals(listOf(Event(id, EXPRESSION_SLOT_CONDITION, HostileException::class.java.name, false)), events)
    }

    @Test
    fun `a condition that never succeeds trips the breaker exactly once at the limit`() {
        repeat(CONDITION_FAILURE_LIMIT - 1) {
            Injections.onBreakpointExpressionEvaluationFailed(NullPointerException(), id, EXPRESSION_SLOT_CONDITION)
        }
        assertEquals(1, events.size)

        repeat(CONDITION_FAILURE_LIMIT) {
            Injections.onBreakpointExpressionEvaluationFailed(NullPointerException(), id, EXPRESSION_SLOT_CONDITION)
        }

        assertEquals(
            listOf(
                Event(id, EXPRESSION_SLOT_CONDITION, "java.lang.NullPointerException", false),
                Event(id, EXPRESSION_SLOT_CONDITION, "java.lang.NullPointerException", true),
            ),
            events,
        )
    }

    @Test
    fun `one successful evaluation exempts the condition from the breaker`() {
        Injections.onBreakpointConditionSucceeded(id)
        repeat(CONDITION_FAILURE_LIMIT * 2) {
            Injections.onBreakpointExpressionEvaluationFailed(NullPointerException(), id, EXPRESSION_SLOT_CONDITION)
        }

        assertEquals(listOf(Event(id, EXPRESSION_SLOT_CONDITION, "java.lang.NullPointerException", false)), events)
    }

    @Test
    fun `concurrent failures trip the breaker exactly once`() {
        val threads = 8
        (1..threads).map {
            thread {
                repeat(CONDITION_FAILURE_LIMIT * 2 / threads) {
                    Injections.onBreakpointExpressionEvaluationFailed(NullPointerException(), id, EXPRESSION_SLOT_CONDITION)
                }
            }
        }.forEach { it.join() }

        assertEquals(1, events.count { it.disabled })
    }

    @Test
    fun `a success racing the limit-reaching failure settles the breaker for good`() {
        repeat(200) { round ->
            val roundId = id + 1 + round
            BreakpointStorage.registerBreakpoint(roundId, Int.MAX_VALUE, "payload")
            repeat(CONDITION_FAILURE_LIMIT - 1) {
                Injections.onBreakpointExpressionEvaluationFailed(NullPointerException(), roundId, EXPRESSION_SLOT_CONDITION)
            }
            val start = CyclicBarrier(2)
            listOf(
                thread { start.await(); Injections.onBreakpointConditionSucceeded(roundId) },
                thread {
                    start.await()
                    Injections.onBreakpointExpressionEvaluationFailed(NullPointerException(), roundId, EXPRESSION_SLOT_CONDITION)
                },
            ).forEach { it.join() }
            // Whichever transition won, the breaker is settled: the success exempts it, or it has already tripped.
            Injections.onBreakpointConditionSucceeded(roundId)
            repeat(CONDITION_FAILURE_LIMIT) {
                Injections.onBreakpointExpressionEvaluationFailed(NullPointerException(), roundId, EXPRESSION_SLOT_CONDITION)
            }

            val trips = synchronized(events) { events.count { it.id == roundId && it.disabled } }
            assertTrue("round $round tripped $trips times", trips <= 1)
        }
    }

    @Test
    fun `watch failures never trip the breaker`() {
        repeat(CONDITION_FAILURE_LIMIT * 2) {
            Injections.onBreakpointExpressionEvaluationFailed(NullPointerException(), id, EXPRESSION_SLOT_WATCH)
        }

        assertEquals(listOf(Event(id, EXPRESSION_SLOT_WATCH, "java.lang.NullPointerException", false)), events)
    }

    @Test
    fun `failures of an unregistered breakpoint are ignored`() {
        Injections.onBreakpointExpressionEvaluationFailed(NullPointerException(), id + 1, EXPRESSION_SLOT_CONDITION)

        assertEquals(emptyList<Event>(), events)
    }
}
