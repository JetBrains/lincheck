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
import org.jetbrains.lincheck.datastructures.scenario
import org.jetbrains.lincheck.util.JdkVersion
import org.jetbrains.lincheck.util.jdkVersion
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LocksTest {

    @Before
    fun setUp() {
        // currently these tests lead to hangs on JDK-21, apparently due to
        // an unrelated bug with Kotlin stdlib arrays/collection util functions instrumentation,
        // see https://github.com/JetBrains/lincheck/issues/564 for details
        assumeFalse((jdkVersion == JdkVersion.JDK_21))
    }

    @Test
    fun testSynchronizedIncrement2() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(
            Triple(0,1,2),
            Triple(1,0,2),
        )
        litmusTest(assertSame(expectedOutcomes)) {
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
        litmusTest(assertSame(expectedOutcomes)) {
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
        litmusTest(assertSame(expectedOutcomes)) {
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
        litmusTest(assertSame(expectedOutcomes, UNKNOWN)) {
            val lock = Object()
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = -1;
            var r2 = -1;
            var r3 = -1;
            var r4 = -1;
            val t1 = thread {
                synchronized(lock) {
                    x.set(1)
                }
            }
            val t2 = thread {
                synchronized(lock) {
                    y.set(1)
                }
            }
            val t3 = thread {
                synchronized(lock) {
                    r1 = x.get()
                }
                synchronized(lock) {
                    r2 = y.get()
                }
            }
            val t4 = thread {
                synchronized(lock) {
                    r3 = y.get()
                }
                synchronized(lock) {
                    r4 = x.get()
                }
            }
            t1.join()
            t2.join()
            t3.join()
            t4.join()
            listOf(r1,r2,r3,r4)
        }
    }

}