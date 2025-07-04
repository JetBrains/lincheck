@file:Suppress("UNUSED")
/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.transformation.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck_test.datastructures.MSQueueBlocking
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.jetbrains.lincheck.datastructures.CTestConfiguration
import org.jetbrains.lincheck.datastructures.RandomProvider
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.reflect.jvm.*


/**
 * Checks that spin-cycle repeated events are cut in case of obstruction freedom violation
 */
class ObstructionFreedomViolationEventsCutTest {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val q = MSQueueBlocking()

    @Operation
    fun enqueue(x: Int) = q.enqueue(x)

    @Operation
    fun dequeue(): Int? = q.dequeue()

    @Test
    fun runModelCheckingTest() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .checkImpl(this::class.java)
        .checkLincheckOutput("obstruction_freedom_violation_events_cut")
}

/**
 * Checks that spin-cycle repeated events are cut in case when spin cycle contains few actions
 */
class SpinlockEventsCutShortLengthTest : AbstractSpinLivelockTest() {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val sharedStateAny = AtomicBoolean(false)

    override val outputFileName: String
        get() = "spin_lock/spin_lock_events_cut_single_action_cycle"

    override fun meaninglessActions() {
        sharedStateAny.get()
    }
}


/**
 * Checks that spin-cycle repeated events are cut in case when spin cycle contains few actions
 */
class SpinlockEventsCutMiddleLengthTest : AbstractSpinLivelockTest() {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val sharedStateAny = AtomicBoolean(false)

    override val outputFileName: String
        get() = "spin_lock/spin_lock_events_cut_two_actions_cycle"
    

    override fun meaninglessActions() {
        val x = sharedStateAny.get()
        sharedStateAny.set(!x)
    }
}

/**
 * Checks that spin-cycle repeated events are cut in case
 * when one thread runs in the infinite loop while others terminate
 */
class SpinlockEventsCutInfiniteLoopTest : AbstractSpinLivelockTest() {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val sharedStateAny = AtomicBoolean(false)

    override val outputFileName: String get() = "spin_lock/infinite_spin_loop_events_cut"

    override fun meaninglessActions() {
        while (true) {
            val x = sharedStateAny.get()
            sharedStateAny.set(!x)
        }
    }
}

/**
 * Checks that spin-cycle repeated events are cut in case
 * when one thread runs in the infinite loop while others terminate
 */
class SpinlockEventsCutInfiniteLoopWithParametersTest : AbstractSpinLivelockTest() {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    @Volatile
    private var sharedState: Boolean = false

    override val outputFileName: String get() = "spin_lock/infinite_spin_loop_events_read_write"

    override fun meaninglessActions() {
        while (true) {
            val x = sharedState
            sharedState = !x
        }
    }
}

/**
 * Checks that spin cycle properly detected, and the spin cycle label is placed correctly
 * when the spin cycle is twice bigger due to a flipping method receivers.
 */
class SpinlockEventsCutInfiniteLoopWithReceiversTest : AbstractSpinLivelockTest() {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val first = Receiver(false)
    private val second = Receiver(false)

    override val outputFileName: String get() = "spin_lock/infinite_spin_loop_events_receivers"

    override fun meaninglessActions() {
        var pickFirst = false
        val firstReceiver = first
        val secondReceiver = second
        while (true) {
            val receiver = if (pickFirst) firstReceiver else secondReceiver
            receiver.value = false
            pickFirst = !pickFirst
        }
    }

    data class Receiver(@Volatile var value: Boolean)
}

/**
 * Checks that spin cycle properly detected, and the spin cycle label is placed correctly
 * when the spin cycle is bigger due to a different arrays usage and cells access.
 */
class SpinlockEventsCutInfiniteLoopWithArrayOperationsTest : AbstractSpinLivelockTest() {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    @Volatile
    private var array: Array<Int> = Array(3) { 0 }

    override val outputFileName: String get() = "spin_lock/infinite_spin_loop_events_arrays"

    override fun meaninglessActions() {
        var index = 0
        var valueToWrite = 0
        while (true) {
            array[index] = valueToWrite

            index = (index + 1) % array.size
            if (index == 0) {
                valueToWrite = (valueToWrite + 1) % 3
            }
        }
    }
}

/**
 * Checks that spin cycle properly detected, and the spin cycle label is placed correctly
 * when the spin cycle is twice bigger due to a flipping arrays receivers usage.
 */
class SpinlockEventsCutInfiniteLoopWithArrayReceiversTest : AbstractSpinLivelockTest() {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val first = Array(3) { 0 }
    private val second = Array(3) { 0 }

    override val outputFileName: String get() = "spin_lock/infinite_spin_loop_events_arrays_receivers"

    override fun meaninglessActions() {
        var pickFirst = false
        val firstReceiver = first
        val secondReceiver = second
        while (true) {
            val receiver = if (pickFirst) firstReceiver else secondReceiver
            receiver[0] = 1
            pickFirst = !pickFirst
        }
    }
}

/**
 * Checks that spin cycle properly detected, and the spin cycle label is placed correctly
 * when spin cycle period can't be found using parameters and receivers, so
 * LinCheck should calculate spin cycle period without params.
 */
class SpinlockEventsCutInfiniteNoCycleWithParamsTest : AbstractSpinLivelockTest() {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val array = Array(3) { 0 }
    private val random = java.util.Random(0)

    override val outputFileName: String get() = "spin_lock/infinite_spin_loop_events_no_cycle_params"

    override fun meaninglessActions() {
        while (true) {
            val value = random.nextInt()
            array[0] = value
            array[0] = value + 1
            array[0] = value + 2
        }
    }
}


/**
 * Checks that spin-cycle repeated events are cut in case when spin cycle contains many actions
 */
class SpinlockEventsCutLongCycleActionsTest : AbstractSpinLivelockTest() {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val data = AtomicReferenceArray<Int>(7)
    override val outputFileName: String
        get() = "spin_lock/spin_lock_events_cut_long_cycle"
    
    override fun meaninglessActions() {
        data[0] = 0
        data[1] = 0
        data[2] = 0
        data[3] = 0
        data[4] = 0
        data[5] = 0
        data[6] = 0
    }

}

/**
 * Checks that spin-cycle repeated events are cut in case when spin cycle contains many actions in nested cycle
 */
class SpinlockEventsCutWithInnerLoopActionsTest : AbstractSpinLivelockTest() {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val data = AtomicReferenceArray<Int>(10)

    override val outputFileName: String
        get() = "spin_lock/spin_lock_events_cut_inner_loop"

    override fun meaninglessActions() {
        for (i in 0 until data.length()) {
            data[i] = 0
        }
    }

}

abstract class AbstractSpinLivelockTest {
    private val sharedState1 = AtomicBoolean(false)
    private val sharedState2 = AtomicBoolean(false)

    abstract val outputFileName: String

    @Operation
    fun one(): Int {
        while (!sharedState1.compareAndSet(false, true)) {
            meaninglessActions()
        }
        while (!sharedState2.compareAndSet(false, true)) {
            meaninglessActions()
        }
        sharedState1.set(false)
        sharedState2.set(false)

        return 1
    }

    @Operation
    fun two(): Int {
        while (!sharedState2.compareAndSet(false, true)) {
            meaninglessActions()
        }
        while (!sharedState1.compareAndSet(false, true)) {
            meaninglessActions()
        }
        sharedState2.set(false)
        sharedState1.set(false)

        return 2
    }

    abstract fun meaninglessActions()

    @Test
    fun testWithModelCheckingStrategy() = ModelCheckingOptions()
        .minimizeFailedScenario(false)
        .checkImpl(this::class.java)
        .checkLincheckOutput(outputFileName)
}

/**
 * Checks that spin-cycle repeated events are shortened
 * when the reason of a failure is not deadlock or obstruction freedom violation (incorrect results failure)
 */
class SpinlockInIncorrectResultsWithClocksTest {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    @Volatile
    private var bStarted = false

    @Operation
    fun a() {
    }

    @Operation
    fun b() {
        bStarted = true
    }

    @Operation
    fun c() {
        while (!bStarted) {
        } // wait until `a()` is completed
    }

    @Operation
    fun d(): Int = 0 // cannot return 0, should fail

    @Test
    fun test() = ModelCheckingOptions()
        .executionGenerator(ClocksTestScenarioGenerator::class.java)
        .iterations(1)
        .sequentialSpecification(ClocksTestSequential::class.java)
        .minimizeFailedScenario(false)
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_lock/spin_lock_in_incorrect_results_failure")


    /**
     * @param randomProvider is required by scenario generator contract
     */
    @Suppress("UNUSED_PARAMETER")
    class ClocksTestScenarioGenerator(
        testCfg: CTestConfiguration,
        testStructure: CTestStructure,
        randomProvider: RandomProvider
    ) : ExecutionGenerator(testCfg, testStructure) {
        override fun nextExecution() = ExecutionScenario(
            initExecution = emptyList(),
            parallelExecution = listOf(
                listOf(
                    Actor(method = SpinlockInIncorrectResultsWithClocksTest::a.javaMethod!!, arguments = emptyList()),
                    Actor(method = SpinlockInIncorrectResultsWithClocksTest::b.javaMethod!!, arguments = emptyList())
                ),
                listOf(
                    Actor(method = SpinlockInIncorrectResultsWithClocksTest::c.javaMethod!!, arguments = emptyList()),
                    Actor(method = SpinlockInIncorrectResultsWithClocksTest::d.javaMethod!!, arguments = emptyList())
                )
            ),
            postExecution = emptyList(),
            validationFunction = null
        )

    }

    class ClocksTestSequential {
        private var x = 0

        fun a() {
            x = 1
        }

        fun b() {}
        fun c() {}

        fun d(): Int = x
    }

}

/**
 * Checks that after a spin-cycle found execution is halted and interleaving is replayed to avoid side effects
 * caused by the multiple executions of the cycle.
 *
 * Test should not fail.
 */
class SpinCycleWithSideEffectsTest {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val counter = AtomicInteger(0)

    private val shouldNotBeVeryBig = AtomicInteger(0)

    @Operation
    fun spinLockCause() {
        counter.incrementAndGet()
        counter.decrementAndGet()
        check(shouldNotBeVeryBig.get() < 50)
    }

    @Operation
    fun spinLock() {
        while (counter.get() != 0) {
            shouldNotBeVeryBig.incrementAndGet()
        }
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(SpinCycleWithSideEffectsTest::spinLockCause) }
                thread { actor(SpinCycleWithSideEffectsTest::spinLock) }
            }
        }
        .invocationsPerIteration(100)
        .iterations(100)
        .check(this::class)

}

/**
 * Checks proper output in case of spin-lock in one thread.
 * Should correctly detect spin cycle and place spin cycle label in case
 * when all potential switch points are nested in non-atomic methods.
 */
class SpinLockWithAllEventsWrappedInMethodsTest {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val counter = AtomicInteger(0)
    private val someUselessSharedState = AtomicBoolean(false)

    @Operation
    fun trigger() {
        counter.incrementAndGet()
        counter.decrementAndGet()
    }

    @Operation
    fun causesSpinLock() {
        if (counter.get() != 0) {
            deadSpinCycle()
        }
    }

    private fun deadSpinCycle() {
        while (true) {
            val value = getSharedVariable()
            action(value)
        }
    }

    private fun getSharedVariable(): Boolean = someUselessSharedState.get()
    private fun action(value: Boolean) = someUselessSharedState.compareAndSet(value, !value)

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(RecursiveSpinLockTest::trigger) }
                thread { actor(RecursiveSpinLockTest::causesSpinLock) }
            }
        }
        .minimizeFailedScenario(false)
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_lock/spin_lock_nested_events")

}

/**
 * Checks that spin cycle properly detected, and the spin cycle label is placed correctly
 * when all the trace points are in the top-level, i.e., right in the actor.
 */
@Ignore // TODO investigate why this goes OOM #632
class SingleThreadTopLevelSpinLockTest {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    @Volatile
    private var state: Boolean = false

    @Operation
    fun spinLock() {
        while (true) {
            state = false
            state = true
        }
    }

    @Test
    fun test() = ModelCheckingOptions()
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_lock/spin_lock_top_level")

}
