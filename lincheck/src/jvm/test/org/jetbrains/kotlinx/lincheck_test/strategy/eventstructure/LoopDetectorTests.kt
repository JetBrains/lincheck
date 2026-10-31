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
import org.jetbrains.kotlinx.lincheck.strategy.ManagedLivelockFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.kotlinx.lincheck.strategy.runIteration
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.scenario
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.withLincheckTestContext
import org.junit.Assert.assertTrue
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

    @Test
    fun testPendingLockCompletesWhileOtherThreadsSpin() {
        val outcomes = setOf(Triple(0, false, 1), Triple(0, true, 1), Triple(1, true, 1))
        for (memoryModel in listOf(MemoryModel.SequentialConsistency, MemoryModel.RelaxedJava)) {
            for (prefixUnlock in listOf(false, true)) {
                litmusTest(
                    assertSame(outcomes, UNKNOWN), memoryModel,
                    invocations = 10_000, requireExhaustion = true, options = boundedOptions(),
                ) {
                    val monitor = Any()
                    var value = 0
                    var ready = false
                    val result = AtomicInteger(0)
                    var observedValue = -1
                    var observedReady = false
                    if (prefixUnlock) synchronized(monitor) {}
                    val joined = thread(start = false) {}
                    val owner = thread(start = false) {
                        val readValue = value
                        observedValue = readValue
                        if (readValue == 0) {
                            synchronized(monitor) { joined.join() }
                        }
                    }
                    val contender = thread(start = false) {
                        ready = true
                        synchronized(monitor) { result.set(1) }
                    }
                    val writer = thread(start = false) {
                        val readReady = ready
                        observedReady = readReady
                        if (readReady) value = 1
                    }
                    // A spinner needs a schedulable peer to exercise the all-spinning case.
                    val spinners = List(2) {
                        thread(start = false) {
                            while (result.get() == 0) {}
                        }
                    }
                    owner.start()
                    contender.start()
                    writer.start()
                    joined.start()
                    spinners.forEach { it.start() }
                    owner.join()
                    contender.join()
                    writer.join()
                    joined.join()
                    spinners.forEach { it.join() }
                    // Include the owner's read so pruning the owner-skipping branch cannot hide a false hang.
                    Triple(observedValue, observedReady, result.get())
                }
            }
        }
    }

    @Test
    fun testLastZeroAfterReplay() {
        class LastZero {
            val array = IntArray(3)

            fun reader(): Int {
                var j = 2
                while (array[j--] != 0) {}
                return j + 1
            }

            fun writer(i: Int): Int {
                val value = array[i - 1] + 1
                array[i] = value
                return value
            }
        }
        val program = scenario {
            parallel {
                thread { actor(LastZero::reader) }
                thread { actor(LastZero::writer, 1) }
                thread { actor(LastZero::writer, 2) }
            }
        }
        litmusTest(LastZero::class.java, program, assertSame(setOf(1 to 2, 2 to 2, 1 to 0, 2 to 0, 1 to 1))) { result ->
            getValue<Int>(result.parallelResults[2].single()!!) to
                getValue<Int>(result.parallelResults[0].single()!!)
        }
    }

    @Test(timeout = TIMEOUT)
    fun testGenuineHangIsReportedDespiteTerminatingAlternative() {
        class ReadOnceMayHang {
            private var value = 0

            fun readOnce(): Int {
                val observed = value
                if (observed == 1) {
                    while (true) {}
                }
                return observed
            }

            fun write() {
                value = 1
            }
        }
        val scenario = scenario {
            parallel {
                thread { actor(ReadOnceMayHang::readOnce) }
                thread { actor(ReadOnceMayHang::write) }
            }
        }
        for (memoryModel in listOf(MemoryModel.SequentialConsistency, MemoryModel.RelaxedJava)) {
            val config = boundedOptions()
                .useExperimentalModelChecking()
                .memoryModel(memoryModel)
                .createTestConfigurations(ReadOnceMayHang::class.java)
            val completedResults = mutableListOf<Int>()
            val verifier = getResultsVerifier { results ->
                completedResults.add(getValue(results.parallelResults[0][0]!!))
                true
            }
            withLincheckTestContext(InstrumentationMode.EXPERIMENTAL_MODEL_CHECKING) {
                config.createStrategy(ReadOnceMayHang::class.java, scenario, validationFunction = null).use { strategy ->
                    val failure = strategy.runIteration(1_000, verifier)
                    assertTrue(
                        "Expected the read-one branch to report a livelock, got $failure",
                        failure is ManagedLivelockFailure,
                    )
                    // The initial read-zero execution must complete before its backward revisit hangs.
                    assertSame(setOf(0), UNKNOWN).verify(completedResults)
                }
            }
        }
    }

    private fun boundedOptions() = ModelCheckingOptions()
        .analyzeStdLib(true)
        .loopIterationsBeforeThreadSwitch(2)
        .loopBound(5)
}
