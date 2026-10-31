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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LocksTest {

    @Test
    fun testSynchronizedIncrement2() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(
            Triple(0,1,2),
            Triple(1,0,2),
        )
        litmusTest(assertSame(expectedOutcomes), requireExhaustion = true) {
            var x = 0
            val monitor = Any()
            var r1 = -1;
            var r2 = -1;

            val t1 = thread {
                synchronized(monitor) {
                    r1 = x++
                }
            }
            val t2 = thread {
                synchronized(monitor) {
                    r2 = x++
                }
            }

            t1.join()
            t2.join()

            Triple(r1, r2, x)
        }
    }

    @Test
    fun testSynchronizedIncrement3() {
        val expectedOutcomes: Set<List<Int>> = setOf(
            listOf(0,1,2,3),
            listOf(0,2,1,3),
            listOf(1,0,2,3),
            listOf(2,0,1,3),
            listOf(1,2,0,3),
            listOf(2,1,0,3),
        )
        litmusTest(assertSame(expectedOutcomes), requireExhaustion = true) {
            var x = 0
            val monitor = Any()
            var r1 = -1;
            var r2 = -1;
            var r3 = -1;

            val t1 = thread {
                synchronized(monitor) {
                    r1 = x++
                }
            }
            val t2 = thread {
                synchronized(monitor) {
                    r2 = x++
                }
            }
            val t3 = thread {
                synchronized(monitor) {
                    r3 = x++
                }
            }

            t1.join()
            t2.join()
            t3.join()

            listOf(r1, r2, r3, x)
        }
    }

    @Test
    fun testSynchronized() {
        val read = PrimitivesTest.SynchronizedVariable::read
        val addAndGet = PrimitivesTest.SynchronizedVariable::addAndGet
        val testScenario = scenario {
            parallel {
                thread {
                    actor(addAndGet, 1)
                }
                thread {
                    actor(addAndGet, 1)
                }
            }
            post {
                actor(read)
            }
        }
        val outcomes: Set<Triple<Int, Int, Int>> = setOf(
            Triple(1, 2, 2),
            Triple(2, 1, 2)
        )
        // TODO: investigate why `executionCount = 3`
        litmusTest(PrimitivesTest.SynchronizedVariable::class.java, testScenario, outcomes) { results ->
            val r1 = getValue<Int>(results.parallelResults[0][0]!!)
            val r2 = getValue<Int>(results.parallelResults[1][0]!!)
            val r3 = getValue<Int>(results.postResults[0]!!)
            Triple(r1, r2, r3)
        }
    }

    @Test
    fun testNotifyWait() {
        val outcomes = setOf(1)
        litmusTest(assertSame(outcomes)) {
            val v = PrimitivesTest.SynchronizedVariable()
            var r1 = -1

            val t1 = thread {
                v.writeAndNotify(1)
            }
            val t2 = thread {
                r1 = v.waitAndRead()
            }

            t1.join()
            t2.join()
            r1
        }
    }

    @Test
    fun testWaitNotify() {
        val outcomes = setOf(1)
        litmusTest(assertSame(outcomes)) {
            val v = PrimitivesTest.SynchronizedVariable()
            var r1 = -1

            val t1 = thread {
                r1 = v.waitAndRead()
            }
            val t2 = thread {
                v.writeAndNotify(1)
            }

            t1.join()
            t2.join()
            r1
        }
    }


    @Test
    fun testReentrantLocks() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(
            Triple(0,1,2),
            Triple(1,0,2),
        )
        litmusTest(assertSame(expectedOutcomes), requireExhaustion = true) {
            var x = 0
            val monitor = Any()
            var r1 = -1;
            var r2 = -1;

            val t1 = thread {
                synchronized(monitor) {
                    synchronized(monitor) {
                        r1 = x++
                    }
                }
            }
            val t2 = thread {
                synchronized(monitor) {
                    synchronized(monitor) {
                        r2 = x++
                    }
                }
            }

            t1.join()
            t2.join()

            Triple(r1, r2, x)
        }
    }

    @Test
    fun testRepeatedReentrantAcquisitions() {
        val outcomes = setOf(Triple(0, 1, 2), Triple(0, 2, 1), Triple(1, 2, 0))
        litmusTest(assertSame(outcomes), requireExhaustion = true) {
            val monitor = Any()
            var counter = 0
            var first = -1
            var second = -1
            var contender = -1
            val repeated = thread {
                synchronized(monitor) {
                    synchronized(monitor) { first = counter++ }
                }
                synchronized(monitor) {
                    synchronized(monitor) { second = counter++ }
                }
            }
            val other = thread {
                synchronized(monitor) { contender = counter++ }
            }
            repeated.join()
            other.join()
            Triple(first, second, contender)
        }
    }

    @Test
    fun testReentrantWaitRestoresMonitorDepth() {
        litmusTest(assertSame(setOf(false to 2, true to 2)), requireExhaustion = true) {
            val monitor = Object()
            var ready = false
            var waited = false
            var counter = 0
            val waiter = thread {
                synchronized(monitor) {
                    synchronized(monitor) {
                        while (!ready) {
                            waited = true
                            monitor.wait()
                        }
                        counter++
                    }
                }
            }
            val notifier = thread {
                synchronized(monitor) {
                    counter++
                    ready = true
                    monitor.notifyAll()
                }
            }
            waiter.join()
            notifier.join()
            waited to counter
        }
    }

    @Test
    fun testNestedLocksRepairedByLaterUnlock() {
        val outcomes = setOf(Triple(0, 1, 2), Triple(1, 0, 2), Triple(1, 0, 1))
        litmusTest(assertSame(outcomes), requireExhaustion = true) {
            val outer = Any()
            val inner = Any()
            var counter = 0
            var nested = -1
            var independent = -1
            var observed = -1
            val first = thread {
                synchronized(outer) {
                    synchronized(inner) { nested = counter++ }
                }
            }
            val second = thread {
                synchronized(inner) { independent = counter++ }
                synchronized(outer) { observed = counter }
            }
            first.join()
            second.join()
            Triple(nested, independent, observed)
        }
    }

    @Test
    fun testPendingReentrantContenders() {
        for (memoryModel in listOf(MemoryModel.SequentialConsistency, MemoryModel.RelaxedJava)) {
            litmusTest(assertSame(permutations(listOf(0, 1, 2))), memoryModel, requireExhaustion = true) {
                val monitor = Any()
                val helper = thread(start = false) {}
                var counter = 0
                val order = IntArray(3) { -1 }
                val contenders = List(3) { index ->
                    thread(start = false) {
                        synchronized(monitor) {
                            synchronized(monitor) {
                                // Make the other requests pending before either owner releases the monitor.
                                if (index < 2) helper.join()
                                order[index] = counter++
                            }
                        }
                    }
                }
                contenders.forEach { it.start() }
                helper.start()
                contenders.forEach { it.join() }
                helper.join()
                order.toList()
            }
        }
    }

    @Test
    fun testMemoryRevisitReordersCriticalSections() {
        for (memoryModel in listOf(MemoryModel.SequentialConsistency, MemoryModel.RelaxedJava)) {
            // The first thread's section can be before, between, or after the other two sections.
            // Only the first order forces the outside read to observe x = 1.
            val outcomes = setOf(0 to 1, 1 to 0, 1 to 1, 2 to 0, 2 to 1)
            litmusTest(assertSame(outcomes), memoryModel, requireExhaustion = true) {
                val monitor = Any()
                var x = 0
                var y = 0
                var a = -1
                var b = -1
                val first = thread(start = false) {
                    x = 1
                    synchronized(monitor) { a = y }
                }
                val second = thread(start = false) {
                    synchronized(monitor) { y = 1 }
                    b = x
                    synchronized(monitor) { y = 2 }
                }
                first.start()
                second.start()
                first.join()
                second.join()
                a to b
            }
        }
    }

    // Some static objects for the test below
    companion object {
        val LOCK1 = Object()
        val LOCK2 = Object()
    }

    @Test
    fun testSeperateStaticLocks() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(
            Triple(0,1,2),
            Triple(1,0,2),
            Triple(0,0,1), // Locks are different so we can have a race
        )
        // NOTE: we get 4 outcomes instead of 3, this is because there are 2 options.
        litmusTest(assertSame(expectedOutcomes, UNKNOWN)) {
            var x = 0
            var r1 = -1;
            var r2 = -1;

            val t1 = thread {
                synchronized(LOCK1) {
                    r1 = x++
                }
            }
            val t2 = thread {
                synchronized(LOCK2) {
                    r2 = x++
                }
            }

            t1.join()
            t2.join()

            Triple(r1, r2, x)
        }
    }


    @Test
    fun testTwoLocksSameThread() {
        val expectedOutcomes: Set<Int> = setOf(
            1,
        )
        litmusTest(assertSame(expectedOutcomes)) {
            val lock = Object()
            val x = AtomicInteger(0)
            var r1 = 0;
            val t1 = thread {
                synchronized(lock) {
                    x.set(1)
                }
                synchronized(lock) {
                    r1 = x.get()
                }
            }
            t1.join()
            r1
        }
    }

    @Test
    fun testSBLock() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(
            (0 to 1),
            (1 to 0),
            (1 to 1),
        )
        litmusTest(assertSame(expectedOutcomes, UNKNOWN)) {
            val lock = Object()
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0;
            var r2 = 0;
            val t1 = thread {
                synchronized(lock) {
                    x.set(1)
                }
                synchronized(lock) {
                    r1 = y.get()
                }
            }
            val t2 = thread {
                synchronized(lock) {
                    y.set(1)
                }
                synchronized(lock) {
                    r2 = x.get()
                }
            }
            t1.join()
            t2.join()
            (r1 to r2)
        }
    }

    @Test
    fun testIRIWLock() {
        val expectedOutcomes: Set<List<Int>> = setOf(
            listOf(0,0,0,0),
            listOf(0,0,1,0),
            listOf(0,0,0,1),
            listOf(0,0,1,1),
            listOf(0,1,0,0),
            listOf(0,1,0,1),
            listOf(0,1,1,0),
            listOf(0,1,1,1),
            listOf(1,0,0,0),
            listOf(1,0,0,1),
            // listOf(1,0,1,0), // This outcome should never happen
            listOf(1,0,1,1),
            listOf(1,1,0,0),
            listOf(1,1,0,1),
            listOf(1,1,1,0),
            listOf(1,1,1,1),
        )
        // All six sections share one mutex; only each reader's two sections have a fixed order.
        // Recording their ordinals distinguishes all 180 ownership chains, including equal-valued reads.
        val expectedExecutions = permutations((0..5).toList())
            .filter { order -> order[2] < order[3] && order[4] < order[5] }
            .map { order ->
                order to listOf(
                    if (order[0] < order[2]) 1 else 0,
                    if (order[1] < order[3]) 1 else 0,
                    if (order[1] < order[4]) 1 else 0,
                    if (order[0] < order[5]) 1 else 0,
                )
            }
            .toSet()
        val verifier = OutcomeVerifier<Pair<List<Int>, List<Int>>> { actualExecutions ->
            assertSame(expectedExecutions, UNKNOWN).verify(actualExecutions)
            assertSame(expectedOutcomes, UNKNOWN).verify(actualExecutions.map { it.second })
            assertTrue(
                "Expected at most 360 accepted executions, got ${actualExecutions.size}",
                actualExecutions.size <= 360,
            )
        }
        litmusTest(verifier, invocations = 100_000, requireExhaustion = true) {
            val lock = Object()
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val order = IntArray(6) { -1 }
            var nextOrdinal = 0
            var r1 = -1;
            var r2 = -1;
            var r3 = -1;
            var r4 = -1;
            val t1 = thread {
                synchronized(lock) {
                    x.set(1)
                    order[0] = nextOrdinal++
                }
            }
            val t2 = thread {
                synchronized(lock) {
                    y.set(1)
                    order[1] = nextOrdinal++
                }
            }
            val t3 = thread {
                synchronized(lock) {
                    r1 = x.get()
                    order[2] = nextOrdinal++
                }
                synchronized(lock) {
                    r2 = y.get()
                    order[3] = nextOrdinal++
                }
            }
            val t4 = thread {
                synchronized(lock) {
                    r3 = y.get()
                    order[4] = nextOrdinal++
                }
                synchronized(lock) {
                    r4 = x.get()
                    order[5] = nextOrdinal++
                }
            }
            t1.join()
            t2.join()
            t3.join()
            t4.join()
            order.toList() to listOf(r1,r2,r3,r4)
        }
    }

    private fun permutations(values: List<Int>): Set<List<Int>> =
        if (values.isEmpty()) setOf(emptyList())
        else values.flatMap { value -> permutations(values - value).map { listOf(value) + it } }.toSet()

}
