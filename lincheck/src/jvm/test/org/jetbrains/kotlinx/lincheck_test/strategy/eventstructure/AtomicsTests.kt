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

import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.lincheck.datastructures.scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AtomicsTests {

    @Test
    fun testRmwBeforeRetainedWrite() {
        for (memoryModel in MemoryModel.entries) {
            litmusTest(assertSame(setOf(2 to 3, 0 to 2)), memoryModel, requireExhaustion = true) {
                val x = AtomicInteger(0)
                var result = 0
                val writer = thread { x.set(2) }
                val incrementer = thread { result = x.getAndIncrement() }
                writer.join()
                incrementer.join()
                result to x.get()
            }
        }
    }

    @Test(timeout = 30_000)
    fun testRmwRevisitReordersRetainedWrites() {
        val outcomes = setOf(
            Triple(1, 0, 1), Triple(1, 1, 11), Triple(1, 2, 1),
            Triple(2, 0, 1), Triple(2, 0, 2), Triple(2, 1, 2),
            Triple(2, 1, 11), Triple(2, 2, 1), Triple(2, 2, 11),
            Triple(11, 1, 11), Triple(11, 2, 1), Triple(11, 2, 11),
        )
        for (memoryModel in MemoryModel.entries) {
            litmusTest(assertSame(outcomes), memoryModel, requireExhaustion = true) {
                val x = AtomicInteger(0)
                var read = 0
                var exchanged = 0
                val writer = thread { x.set(1) }
                val reader = thread {
                    x.set(2)
                    read = x.get()
                }
                val exchanger = thread { exchanged = x.getAndSet(11) }
                writer.join()
                reader.join()
                exchanger.join()
                Triple(read, exchanged, x.get())
            }
        }
    }

    @Test
    fun testFetchAndAddChainsSkipRejectedForwardChoices() {
        // Each of the 4! orders of the increments is a distinct execution.
        // A forward choice of an increment's read onto a write already consumed by a completed increment
        // is resolved inside the event structure instead of being executed and rejected,
        // so the 24 executions need fewer rejected invocations (10) than executing those choices would (55).
        litmusTest(
            assertSame(setOf(4), 24),
            MemoryModel.SequentialConsistency,
            requireExhaustion = true,
            checkStats = { stats ->
                assertTrue(stats.inconsistentInvocations < stats.consistentInvocations)
            },
        ) {
            val x = AtomicInteger(0)
            val t1 = thread { x.getAndIncrement() }
            val t2 = thread { x.getAndIncrement() }
            val t3 = thread { x.getAndIncrement() }
            val t4 = thread { x.getAndIncrement() }
            t1.join()
            t2.join()
            t3.join()
            t4.join()
            x.get()
        }
    }

    @Test
    fun testOverwrittenWriteChoicesAreNotCreated() {
        // A read request synchronizes only with the writes it can read from consistently:
        // the writes overwritten in its happens-before prefix are filtered out under every memory model.
        // Here `r1` (same thread as the writes) and `r2` (after joining the writer) must read 2,
        // so no invocation is spent on their forward choices of 0 or 1; only `r3` races with the writes.
        for (memoryModel in MemoryModel.entries) {
            litmusTest(
                assertSame(setOf(Triple(2, 2, 0), Triple(2, 2, 1), Triple(2, 2, 2))),
                memoryModel,
                requireExhaustion = true,
                checkStats = { stats -> assertEquals(0, stats.inconsistentInvocations) },
            ) {
                val x = AtomicInteger(0)
                var r1 = 0
                var r3 = 0
                val writer = thread {
                    x.set(1)
                    x.set(2)
                    r1 = x.get()
                }
                val racer = thread { r3 = x.get() }
                writer.join()
                val r2 = x.get()
                racer.join()
                Triple(r1, r2, r3)
            }
        }
    }

    @Test
    fun testThreeFAA() {
        val outcomes = setOf<List<Int>>(
            listOf(0,1,2),
            listOf(0,2,1),
            listOf(1,0,2),
            listOf(1,2,0),
            listOf(2,0,1),
            listOf(2,1,0),
        )
        litmusTest(assertSame(outcomes)) {
            val x = AtomicInteger(0)
            var r0 = -1
            var r1 = -1
            var r2 = -1

            val t0 = thread {
                r0 = x.getAndIncrement()
            }
            val t1 = thread {
                r1 = x.getAndIncrement()
            }
            val t2 = thread {
                r2 = x.getAndIncrement()
            }

            t0.join()
            t1.join()
            t2.join()

            listOf(r0, r1, r2)
        }
    }

    @Test
    fun testRMW3_1() {
        val outcomes = setOf<List<Int>>(
            listOf(0,1,2,3),
            listOf(0,3,1,2),
            listOf(0,2,1,3),
            listOf(2,3,0,1),
            listOf(1,2,0,3),
            listOf(1,3,0,2),
        )
        litmusTest(assertSame(outcomes)) {
            val x = AtomicInteger(0)

            val r = IntArray(4)

            val t1 = thread {
                r[0] = x.getAndIncrement()
                r[1] = x.getAndIncrement()
            }
            val t2 = thread {
                r[2] = x.getAndIncrement()
                r[3] = x.getAndIncrement()
            }

            t1.join()
            t2.join()

            listOf(r[0], r[1], r[2], r[3])
        }
    }

    @Test
    fun testRMW3_2() {
        val outcomes = setOf<List<Int>>(
            listOf(0,1,2,3),
            listOf(0,3,1,2),
            listOf(0,2,1,3),
            listOf(2,3,0,1),
            listOf(1,2,0,3),
            listOf(1,3,0,2),
        )
        class TestClass {
            val x = AtomicInteger(0)
            fun one(): Pair<Int, Int> {
                val r1 = x.getAndIncrement()
                val r2 = x.getAndIncrement()
                return r1 to r2
            }

            fun two(): Pair<Int, Int> {
                val r3 = x.getAndIncrement()
                val r4 = x.getAndIncrement()
                return r3 to r4
            }
        }

        val scenario = scenario {
            parallel {
                thread { actor(TestClass::one) }
                thread { actor(TestClass::two) }
            }
        }

        litmusTest(TestClass::class.java, scenario, assertSame(outcomes)) { results ->
            val p1 = getValue<Pair<Int, Int>>(results.parallelResults[0][0]!!)
            val p2 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)

            listOf(p1.first, p1.second, p2.first, p2.second)
        }
    }


    @Test
    fun testCAS() {
        val outcomes = setOf<List<Int>>(
            listOf(1,0,0),
            listOf(0,1,0),
            listOf(0,0,1),
        )
        litmusTest(assertSame(outcomes)) {
            val x = AtomicInteger(0)
            val r = IntArray(3)

            val t1 = thread {
                r[0] = if (x.compareAndSet(0, 1)) 1 else 0
            }
            val t2 = thread {
                r[1] = if (x.compareAndSet(0, 1)) 1 else 0
            }
            val t3 = thread {
                r[2] = if (x.compareAndSet(0, 1)) 1 else 0
            }

            t1.join()
            t2.join()
            t3.join()

            listOf(r[0], r[1], r[2])
        }
    }

}
