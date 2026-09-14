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
import org.jetbrains.kotlinx.lincheck_test.datastructures.MSQueueBlocking
import org.jetbrains.lincheck.datastructures.scenario
import org.junit.Ignore
import org.junit.Test
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.reflect.KFunction

// TODO: needs better name. Currently just a group of oddball unit tests containing examples that failed at some point in time
class FunnyTests {

    @Test
    fun testConcurrentHashMap() {
        val executionScenario = scenario {
            parallel {
                thread {
                    actor(ConcurrentHashMap<Int, Int>::get, 4)
                    actor(ConcurrentHashMap<Int, Int>::put, 3, 4)
                }
                thread {
                    actor(ConcurrentHashMap<Int, Int>::put, 3, 4)
                    actor(ConcurrentHashMap<Int, Int>::get, 2)
                }
            }
        }
        litmusTest(ConcurrentHashMap::class.java, executionScenario, assertSame(setOf(1), UNKNOWN)) { results -> 1 }
    }

    @Test
    fun testConcurrentLinkedDeque() {
        val executionScenario = scenario {
            parallel {
                thread {
                    actor(ConcurrentLinkedQueue<Int>::offer, 0)
                }
                thread {
                    actor(ConcurrentLinkedQueue<Int>::offer, 1)
                }
            }
            post {
                actor(ConcurrentLinkedQueue<Int>::poll)
                actor(ConcurrentLinkedQueue<Int>::poll)
            }
        }
        val outcomes = setOf(1 to 0, 0 to 1)
        litmusTest(ConcurrentLinkedQueue::class.java, executionScenario, assertSame(outcomes, UNKNOWN)) { results ->
            val r1 = getValue<Int?>(results.postResults[0]!!)
            val r2 = getValue<Int?>(results.postResults[1]!!)
            r1 to r2
        }
    }

    @Test
    fun testConcurrentSkipListMap() {
        val put = ConcurrentSkipListMap<Int, Int>::put;
        val remove: (ConcurrentSkipListMap<Int, Int>, Int) -> Int? = ConcurrentSkipListMap<Int, Int>::remove
        val get: (ConcurrentSkipListMap<Int, Int>, Int) -> Int? = ConcurrentSkipListMap<Int, Int>::get
        val executionScenario = scenario {
            parallel {
                thread {
                    actor(put, 0, 0)
                    actor(ConcurrentSkipListMap<Int, Int>::get, 0)
                }
                thread {
                    actor(remove as KFunction<*>, 1)
                }
            }
        }
        val outcomes = setOf(Triple(null, null, 0), Triple(null, null, null))
        litmusTest(
            ConcurrentSkipListMap::class.java, executionScenario, assertSame(outcomes, UNKNOWN),
            MemoryModel.JAM21) { results ->
            val r1 = getValue<Int?>(results.parallelResults[0][0]!!)
            val r2 = getValue<Int?>(results.parallelResults[1][0]!!)
            val r3 = getValue<Int?>(results.parallelResults[0][1]!!)
            Triple(r1, r2, r3)
        }
    }

    @Test
    fun testMpFencesNotTransitive() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 1, 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)

            var r0 = -1
            var r1 = -1
            var r2 = -1

            val t0 = thread {
                x.setPlain(1)
                VarHandle.releaseFence()
                y.setPlain(1)
            }

            val t1 = thread {
                r0 = y.getPlain()
                z.setPlain(1)
            }

            val t2 = thread {
                r1 = z.getPlain()
                VarHandle.acquireFence()
                r2 = x.getPlain()
            }

            t0.join()
            t1.join()
            t2.join()

            Triple(r0, r1, r2)
        }
    }

    @Test
    fun testMpReleaseWriteAcquireFence() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(
            Triple(1, 1, 1),
            Triple(1, 0, 1),
            Triple(0, 1, 1),
            Triple(0, 1, 0),
            Triple(0, 0, 1),
            Triple(0, 0, 0)
        )
        litmusTest(assertSame(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val flag = AtomicInteger(0)

            var r0 = -1
            var r1 = -1
            var r2 = -1

            val t0 = thread {
                x.setPlain(1)
                y.setPlain(1)
                flag.setRelease(1)
            }

            val t1 = thread {
                r0 = flag.getPlain()
                r1 = x.getPlain()
                VarHandle.acquireFence()
                r2 = y.getPlain()
            }

            t0.join()
            t1.join()

            Triple(r0, r1, r2)
        }
    }

    @Test
    fun testMpReleaseFenceAcquireWrite() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(
            Triple(1, 1, 1),
            Triple(1, 1, 0),
//            Triple(1, 0, 1),
//            Triple(1, 0, 0),
            Triple(0, 1, 1),
            Triple(0, 1, 0),
            Triple(0, 0, 1),
            Triple(0, 0, 0)
        )
        litmusTest(assertSame(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val flag = AtomicInteger(0)

            var r0 = -1
            var r1 = -1
            var r2 = -1

            val t0 = thread {
                x.setPlain(1)
                VarHandle.releaseFence()
                y.setPlain(1)
                flag.setPlain(1)
            }

            val t1 = thread {
                r0 = flag.getAcquire()
                r1 = x.getPlain()
                r2 = y.getPlain()
            }

            t0.join()
            t1.join()

            Triple(r0, r1, r2)
        }
    }


    @Test
    fun testManyRMW() {

        val outcomes = setOf<Triple<Int, Int, Int>>(
            Triple(0, 1, 2),
            Triple(0, 2, 1),
            Triple(1, 0, 2),
            Triple(1, 2, 0),
            Triple(2, 0, 1),
            Triple(2, 1, 0),
        )
        litmusTest(assertSame(outcomes)) {
            val x = AtomicInteger(0)

            val results = IntArray(3)

            val t1 = thread {
                results[0] = x.getAndIncrement()
            }
            val t2 = thread {
                results[1] = x.getAndIncrement()
            }
            val t3 = thread {
                results[2] = x.getAndIncrement()
            }

            t1.join()
            t2.join()
            t3.join()

            Triple(results[0], results[1], results[2])
        }
    }

    @Test
    fun testRMW4() {

        val outcomes = setOf<List<Int>>(
            listOf(0, 1, 2, 3),
            listOf(0, 1, 3, 2),
            listOf(0, 2, 1, 3),
            listOf(0, 2, 3, 1),
            listOf(0, 3, 1, 2),
            listOf(0, 3, 2, 1),

            listOf(1, 0, 2, 3),
            listOf(1, 0, 3, 2),
            listOf(1, 2, 0, 3),
            listOf(1, 2, 3, 0),
            listOf(1, 3, 0, 2),
            listOf(1, 3, 2, 0),

            listOf(2, 0, 1, 3),
            listOf(2, 0, 3, 1),
            listOf(2, 1, 0, 3),
            listOf(2, 1, 3, 0),
            listOf(2, 3, 0, 1),
            listOf(2, 3, 1, 0),

            listOf(3, 0, 1, 2),
            listOf(3, 0, 2, 1),
            listOf(3, 1, 0, 2),
            listOf(3, 1, 2, 0),
            listOf(3, 2, 0, 1),
            listOf(3, 2, 1, 0),
        )
        litmusTest(assertSame(outcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val results = IntArray(4)

            val t1 = thread {
                results[0] = x.getAndIncrement()
            }
            val t2 = thread {
                results[1] = x.getAndIncrement()
            }
            val t3 = thread {
                results[2] = x.getAndIncrement()
            }
            val t4 = thread {
                results[3] = x.getAndIncrement()
            }

            t1.join()
            t2.join()
            t3.join()
            t4.join()

            listOf(results[0], results[1], results[2], results[3])
        }
    }


    @Test
    fun testForcedRmwRR() {
        val outcomes = setOf<List<Int>>(
            listOf(0, 0, 0),
            listOf(0, 0, 1),
            listOf(0, 0, 42),
            listOf(0, 1, 1),
            listOf(0, 1, 42),
            listOf(0, 42, 42),
            // The 0,42,43 // should be impossible
            listOf(42, 0, 0),
            listOf(42, 0, 42),
            listOf(42, 0, 43),
            listOf(42, 42, 42),
            listOf(42, 42, 43),
            listOf(42, 43, 43),
        )
        litmusTest(assertSame(outcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val r = IntArray(3)

            val t1 = thread {
                r[0] = x.getAndIncrement()
            }

            val t2 = thread {
                x.setOpaque(42)
            }

            val t3 = thread {
                r[1] = x.getOpaque()
                r[2] = x.getOpaque()
            }

            t1.join()
            t2.join()
            t3.join()

            listOf(r[0], r[1], r[2])
        }
    }

    @Test
    fun testForcedRmwWW() {
        val outcomes = setOf<List<Int>>(
            listOf(42),
        )
        litmusTest(assertSame(outcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val r = IntArray(1)

            val t1 = thread {
                x.setOpaque(42)
                r[0] = x.getAndIncrement()
            }

            t1.join()

            listOf(r[0])
        }
    }

    @Test
    fun testForcedRmwWR() {
        val outcomes = setOf<List<Int>>(
            // (0, 1) outcome should not happen
            listOf(0, 42),
            listOf(42, 43),
            listOf(42, 42),
        )
        litmusTest(assertSame(outcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val r = IntArray(2)

            val t1 = thread {
                r[0] = x.getAndIncrement()
            }
            val t2 = thread {
                x.setOpaque(42)
                r[1] = x.getOpaque()
            }

            t1.join()
            t2.join()

            listOf(r[0], r[1])
        }
    }

    @Test
    fun testForcedRmwRW() {
        val outcomes = setOf<List<Int>>(
            listOf(0, 0),
            listOf(0, 42),
            listOf(42, 42),
            // (42, 0) should not happen
        )
        litmusTest(assertSame(outcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val r = IntArray(2)

            val t1 = thread {
                r[0] = x.getOpaque()
                r[1] = x.getAndIncrement()
            }
            val t2 = thread {
                x.setOpaque(42)
            }

            t1.join()
            t2.join()

            listOf(r[0], r[1])
        }
    }

    class MyAtomicInteger {

        @JvmField
        var value: Int = 0

        constructor(initialValue: Int) {; // This is just to shut up the checker
            handle.setVolatile(this, initialValue)
        }

        companion object {
            private val handle = run {
                val lookup = MethodHandles.lookup()
                lookup.findVarHandle(MyAtomicInteger::class.java, "value", Int::class.javaPrimitiveType)
            }
        }

        fun getAndSetAcquire(value: Int): Int = handle.getAndSetAcquire(this, value) as Int
        fun getAndSet(value: Int): Int = handle.getAndSet(this, value) as Int
        fun get(): Int = handle.get(this) as Int
        fun getOpaque(): Int = handle.getOpaque(this) as Int
        fun getAcquire(): Int = handle.getAcquire(this) as Int
    }

    @Test
    fun testRMW2() {
        // TODO: fix this annoying aah test case
        val outcomes = setOf<List<Int>>(
            listOf(0,1,2,3,3),
            listOf(0,1,2,2,3),
            listOf(0,1,2,2,2),
            listOf(0,1,2,1,3),
            listOf(0,1,2,1,2),
            listOf(0,1,2,1,1),
            listOf(0,1,2,0,3),
            listOf(0,1,2,0,2),
            listOf(0,1,2,0,1),
            listOf(0,1,2,0,0),
            // 0,2,1
            listOf(0,3,1,3,3),
            listOf(0,3,1,3,2),
            listOf(0,3,1,2,2),
            listOf(0,3,1,1,3),
            listOf(0,3,1,1,2),
            listOf(0,3,1,1,1),
            listOf(0,3,1,0,3),
            listOf(0,3,1,0,2),
            listOf(0,3,1,0,1),
            listOf(0,3,1,0,0),
            //  1,0,2
            listOf(2,0,1,3,3),
            listOf(2,0,1,2,3),
            listOf(2,0,1,2,2),
            listOf(2,0,1,2,1),
            listOf(2,0,1,1,3),
            listOf(2,0,1,1,1),
            listOf(2,0,1,0,3),
            listOf(2,0,1,0,2),
            listOf(2,0,1,0,1),
            listOf(2,0,1,0,0),
            // 1,2,0
            listOf(3,1,0,3,3),
            listOf(3,1,0,3,2),
            listOf(3,1,0,3,1),
            listOf(3,1,0,2,2),
            listOf(3,1,0,1,2),
            listOf(3,1,0,1,1),
            listOf(3,1,0,0,3),
            listOf(3,1,0,0,2),
            listOf(3,1,0,0,1),
            listOf(3,1,0,0,0),
            // 2,0,1
            listOf(3,0,2,3,3),
            listOf(3,0,2,3,1),
            listOf(3,0,2,2,3),
            listOf(3,0,2,2,2),
            listOf(3,0,2,2,1),
            listOf(3,0,2,1,1),
            listOf(3,0,2,0,3),
            listOf(3,0,2,0,2),
            listOf(3,0,2,0,1),
            listOf(3,0,2,0,0),
            // 2,1,0
            listOf(2,3,0,3,3),
            listOf(2,3,0,3,2),
            listOf(2,3,0,3,1),
            listOf(2,3,0,2,2),
            listOf(2,3,0,2,1),
            listOf(2,3,0,1,1),
            listOf(2,3,0,0,3),
            listOf(2,3,0,0,2),
            listOf(2,3,0,0,1),
            listOf(2,3,0,0,0),
        )
        litmusTest(assertSame(outcomes), MemoryModel.JAM21) {
            val x = MyAtomicInteger(0)

            val r = IntArray(5)

            val t1 = thread {
                r[0] = x.getAndSetAcquire(1)
            }
            val t2 = thread {
                r[1] = x.getAndSetAcquire(2)
            }
            val t3 = thread {
                r[2] = x.getAndSetAcquire(3)
            }
            val t4 = thread {
                r[3] = x.getOpaque()
                r[4] = x.getOpaque()
            }

            t1.join()
            t2.join()
            t3.join()
            t4.join()

            listOf(r[0], r[1], r[2], r[3], r[4])
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
                thread{ actor(TestClass::one)}
                thread{ actor(TestClass::two)}
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
        litmusTest(assertSame(outcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val r = IntArray(3)

            val t1 = thread {
                r[0] = if(x.compareAndSet(0, 1)) 1 else 0
            }
            val t2 = thread {
                r[1] = if(x.compareAndSet(0, 1)) 1 else 0
            }
            val t3 = thread {
                r[2] = if(x.compareAndSet(0, 1)) 1 else 0
            }

            t1.join()
            t2.join()
            t3.join()

            listOf(r[0], r[1], r[2])
        }
    }

    @Test
    fun testCAS2() {
        val outcomes = setOf<List<Int>>(
            listOf(1,0),
            listOf(0,1)
        )
        litmusTest(assertSame(outcomes)) {
            val x = AtomicInteger(0)
            val r = IntArray(2)

            val t1 = thread {
                r[0] = if(x.weakCompareAndSetPlain(0, 3)) 1 else 0
                if(r[0] == 0) r[1] = x.getAndIncrement()
            }

            val t2 = thread {
                x.setOpaque(1)
            }

            t1.join()
            t2.join()

            listOf(r[0], r[1])
        }
    }

    @Test
    fun testLastZero10Scenario() {
        class LastZero {
            val N = 10
            val array = IntArray(N+1) { 0 }

            constructor() {}

            fun reader() {
                var j = N
                while (array[j--] != 0) {}
            }

            fun writer(i: Int) {
                array[i] = array[i-1] + 1
            }
        }

        val reader = LastZero::reader
        val writer = LastZero::writer

        val testScenario = scenario {
            parallel {
                thread { actor(reader) }
                thread { actor(writer, 1) }
                thread { actor(writer, 2) }
                thread { actor(writer, 3) }
                thread { actor(writer, 4) }
                thread { actor(writer, 5) }
                thread { actor(writer, 6) }
                thread { actor(writer, 7) }
                thread { actor(writer, 8) }
                thread { actor(writer, 9) }
                thread { actor(writer, 10) }
            }
        }

        litmusTest(
            LastZero::class.java,
            testScenario,
            assertSame(setOf(1), 3328),
            MemoryModel.SequentialConsistency,
            10_000
        ) { 1 }
    }

    @Test
    fun testSkipListMapFailMini() {
        val outcomes = setOf<List<Int>>(
            listOf(1,1),
            listOf(1,0),
            listOf(0,1),
            listOf(0,0),
        )
        litmusTest(assertSame(outcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0);
            val flag = AtomicInteger(0);

            var r1 = -1;
            var r2 = -1;

            val t1 = thread {
                x.setPlain(1)
                flag.compareAndSet(0,1);
            }

            val t2 = thread {
                VarHandle.acquireFence();
                r1 = flag.getPlain();
                r2 = x.getPlain();
            }

            t1.join();
            t2.join();

            listOf(r1, r2)
        }
    }


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
        litmusTest(TestClass::class.java, scenario, assertSame(outcomes, UNKNOWN), MemoryModel.JAM21) { results ->
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
        litmusTest(TestClass::class.java, scenario, assertSame(outcomes, UNKNOWN), MemoryModel.JAM21) { results ->
            val p1 = getValue<Int?>(results.parallelResults[0][0]!!)
            p1
        }
    }


    @Test
    fun testSpinLoop() {
        val outcomes = setOf<List<Int>>(
            listOf(42)
        )

        litmusTest(assertSame(outcomes, 11), MemoryModel.SequentialConsistency) {
            val x = AtomicInteger(0)
            var r1 = -1

            val t1 = thread {
                while(x.get() == 0) {}
                r1 = x.get()
            }

            val t2 = thread {
                x.set(42)
            }

            t1.join()
            t2.join()

            listOf(r1)
        }
    }

    @Test
    fun testSpinLoop2() {
        val outcomes = setOf<List<Int>>(
            listOf(42)
        )

        // NOTE: execution count right now should be 1 + whatever the SPIN_BOUND is in [filterSpinLoopCandidates]
        litmusTest(assertSame(outcomes, 11), MemoryModel.SequentialConsistency, 250) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = -1

            val t1 = thread {
                x.set(42)
            }

            val t2 = thread {
                while (x.get() == 0) {}
                r1 = x.get()
            }

            t1.join()
            t2.join()

            listOf(r1)
        }
    }



    @Test
    fun MSqueueBlockingTest() {
        class TestClass {
            private val queue = MSQueueBlocking()
            fun offer(x: Int) = queue.enqueue(x)
            fun poll(): Int? = queue.dequeue()
        }

        val scenario = scenario {
            parallel {
                thread {
                    actor(TestClass::offer, 1)
                }
                thread {
                    actor(TestClass::offer, 2)
                }
            }
            post {
                actor(TestClass::poll)
            }
        }

        val outcomes = setOf(1, 2)
        litmusTest(TestClass::class.java, scenario, assertSame(outcomes, UNKNOWN), MemoryModel.JAM21, 1000) { results ->
            val r1 = getValue<Int?>(results.postResults[0]!!)
            r1
        }
    }

    @Ignore("We do not properly catch the deadlock error! But it does get raised!")
    @Test
    fun testSimpleDeadLock() {
        val outcomes = setOf<List<Int>>()

        litmusTest(assertSame(outcomes)) {
            val t1 = thread {
                while(true) {}
            }
            val t2 = thread {
                while(true) {}
            }

            t1.join()
            t2.join()

            1 // Should be unreachable as we are in a deadlock
        }
    }

    @Ignore("We do not properly catch the deadlock error!")
    @Test
    fun testDiningPhilosophers() {
        val outcomes = setOf(
            listOf(1), listOf(2)
        )

        litmusTest(assertSame(outcomes, UNKNOWN)) {
            val l1 = Object()
            val l2 = Object()
            var x = -1

            val t1 = thread {
                synchronized(l1) {
                    synchronized(l2) {
                        x = 1
                    }
                }
            }

            val t2 = thread {
                synchronized(l2) {
                    synchronized(l1) {
                        x = 2
                    }
                }
            }

            t1.join()
            t2.join()

            x
        }
    }
}