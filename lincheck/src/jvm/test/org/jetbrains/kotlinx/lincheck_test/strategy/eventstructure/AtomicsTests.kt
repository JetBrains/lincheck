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
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AtomicsTests {

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