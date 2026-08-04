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

import org.jctools.queues.atomic.MpscLinkedAtomicQueue
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.lincheck.datastructures.scenario
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LoopDetectorTests {

    @Test
    fun testMPSCQueueTest() {
        class TestClass {
            private val queue = MpscLinkedAtomicQueue<Int>()
            fun offer(x: Int) = queue.offer(x)
            fun poll(): Int? = queue.poll()
            fun peek(): Int? = queue.peek()
        }

        val scenario = scenario {
            parallel {
                thread {
                    actor(TestClass::offer, 1)
                }
                thread {
                    actor(TestClass::poll)
                }
            }
        }

        val outcomes = setOf(null, 1)
        litmusTest(TestClass::class.java, scenario, assertSame(outcomes), MemoryModel.SequentialConsistency) { results ->
            val p1 = getValue<Int?>(results.parallelResults[1][0]!!)
            p1
        }
    }

    @Test
    fun testMPSCQueueTest2() {
        class TestClass {
            private val queue = MpscLinkedAtomicQueue<Int>()
            fun offer(x: Int) = queue.offer(x)
            fun poll(): Int? = queue.poll()
            fun peek(): Int? = queue.peek()
        }

        val scenario = scenario {
            parallel {
                thread {
                    actor(TestClass::peek)
                }
                thread {
                    actor(TestClass::offer, 1)
                }
            }
        }

        val outcomes = setOf(null, 1)
        litmusTest(TestClass::class.java, scenario, assertSame(outcomes), MemoryModel.SequentialConsistency, 10) { results ->
            val p1 = getValue<Int?>(results.parallelResults[0][0]!!)
            p1
        }
    }


    @Test
    fun testSpinLoop() {
        val outcomes = setOf<List<Int>>(
            listOf(1)
        )

        litmusTest(assertSame(outcomes), MemoryModel.SequentialConsistency, 100) {
            val x = AtomicInteger(0)

            val t1 = thread {
                while (x.get() == 0) {
                }
            }

            val t2 = thread {
                x.set(42)
            }

            t1.join()
            t2.join()

            1
        }
    }

    @Test
    fun testSpinLoop2() {
        val outcomes = setOf<List<Int>>(
            listOf(1)
        )

        litmusTest(assertSame(outcomes), MemoryModel.SequentialConsistency, 10) {
            val x = AtomicInteger(0)


            val t1 = thread {
                x.set(42)
            }

            val t2 = thread {
                while (x.get() == 0) {
                }
            }

            t1.join()
            t2.join()

            1
        }
    }
}