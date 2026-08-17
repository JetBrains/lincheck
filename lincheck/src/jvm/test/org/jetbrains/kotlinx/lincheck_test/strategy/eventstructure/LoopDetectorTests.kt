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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * These tests check the integration of the loop detector with the event structure strategy.
 * Tests contain spin loops which will hopefully be detected by the loop detector.
 * If the strategy integration is correct, then we should also be able to detect the cases
 * where we repeatedly revisit a read which causes the loop to not terminate.
 */
class LoopDetectorTests {

    @Test
    fun testMPSCQueueTest() {
        class TestClass {
            //NOTE: this data structure contains the [spinWaitForNextNode] method, which
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
        // NOTE: the number of executions depends on the loop detector bound
        litmusTest(
            TestClass::class.java,
            scenario,
            assertSame(outcomes, UNKNOWN),
            memoryModel = MemoryModel.SequentialConsistency
        ) { results ->
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
        // NOTE: the number of executions depends on the loop detector bound
        litmusTest(
            TestClass::class.java,
            scenario,
            assertSame(outcomes, UNKNOWN),
            memoryModel = MemoryModel.SequentialConsistency,
            invocations = 10
        ) { results ->
            val p1 = getValue<Int?>(results.parallelResults[0][0]!!)
            p1
        }
    }


    @Test
    fun testSpinLoop() {
        val outcomes = setOf<List<Int>>(
            listOf(42)
        )

        litmusTest(
            assertSame(outcomes, UNKNOWN),
            memoryModel = MemoryModel.SequentialConsistency,
            invocations = 100,
        ) {
            val x = AtomicInteger(0)
            var r0 = -1

            val t1 = thread {
                while (x.get() == 0) {
                }
                r0 = x.get()
            }

            val t2 = thread {
                x.set(42)
            }

            t1.join()
            t2.join()

            listOf(r0)
        }
    }

    @Test
    fun testSpinLoop2() {
        val outcomes = setOf<List<Int>>(listOf(42))

        litmusTest(
            assertSame(outcomes, UNKNOWN),
            memoryModel = MemoryModel.SequentialConsistency,
            invocations = 10
        ) {
            val x = AtomicInteger(0)

            var r0 = -1


            val t1 = thread {
                x.set(42)
            }

            val t2 = thread {
                while (x.get() == 0) {
                }
                r0 = x.get()
            }

            t1.join()
            t2.join()

            listOf(42)
        }
    }

    @Test
    fun testBlockingMsQueueSpinLoop() {
        // Contains a loop shape to the loop inside the enqueue operation a blocking MS queue [MSQueueBlocking]
        val outcomes = setOf<List<Int>>(listOf(42))

        litmusTest(assertSame(outcomes, UNKNOWN),
            memoryModel = MemoryModel.SequentialConsistency,
            invocations = 10
        ) {
            val x = AtomicReference<AtomicInteger>(AtomicInteger(0))
            var r0 = -1

            val t1 = thread {
                while(true) {
                    val r0 = x.get()
                    if (r0.compareAndSet(0, 1)) {
                        x.set(AtomicInteger(0))
                        break
                    }
                }
            }

            val t2 = thread {
                while(true) {
                    val r0 = x.get()
                    if (r0.compareAndSet(0, 2)) {
                        x.set(AtomicInteger(0))
                        break
                    }
                }
            }

            t1.join()
            t2.join()

            listOf(42)
        }
    }
}