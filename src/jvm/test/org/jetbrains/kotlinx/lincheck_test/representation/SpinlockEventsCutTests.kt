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
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.guide.MSQueueBlocking
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.reflect.jvm.*

/**
 * Checks that spin-cycle repeated events are cut in case of obstruction freedom violation
 */
class ObstructionFreedomViolationEventsCutTest {
    private val q = MSQueueBlocking()

    @Operation
    fun enqueue(x: Int) = q.enqueue(x)

    @Operation
    fun dequeue(): Int? = q.dequeue()

    @Test
    fun runModelCheckingTest() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .checkImpl(this::class.java)
        .checkLincheckOutput("obstruction_freedom_violation_events_cut.txt")

}

/**
 * Checks that spin-cycle repeated events are cut in case when spin cycle contains few actions
 */
class SpinlockEventsCutShortLengthTest : AbstractSpinLivelockTest() {

    private val sharedStateAny = AtomicBoolean(false)

    override val outputFileName: String get() = "spin_lock_events_cut_single_action_cycle.txt"

    override fun meaninglessActions() {
        sharedStateAny.get()
    }
}


/**
 * Checks that spin-cycle repeated events are cut in case when spin cycle contains few actions
 */
class SpinlockEventsCutMiddleLengthTest : AbstractSpinLivelockTest() {

    private val sharedStateAny = AtomicBoolean(false)

    override val outputFileName: String get() = "spin_lock_events_cut_two_actions_cycle.txt"

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

    private val sharedStateAny = AtomicBoolean(false)

    override val outputFileName: String get() = "infinite_spin_loop_events_cut.txt"

    override fun meaninglessActions() {
        while (true) {
            val x = sharedStateAny.get()
            sharedStateAny.set(!x)
        }
    }
}

/**
 * Checks that spin-cycle repeated events are cut in case when spin cycle contains many actions
 */
class SpinlockEventsCutLongCycleActionsTest : AbstractSpinLivelockTest() {

    private val data = AtomicReferenceArray<Int>(7)
    override val outputFileName: String get() = "spin_lock_events_cut_long_cycle.txt"
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

    private val data = AtomicReferenceArray<Int>(10)
    override val outputFileName: String get() = "spin_lock_events_cut_inner_loop.txt"
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
        .addCustomScenario {
            parallel {
                thread { actor(AbstractSpinLivelockTest::one) }
                thread { actor(AbstractSpinLivelockTest::two) }
            }
        }
        .minimizeFailedScenario(false)
        .checkImpl(this::class.java)
        .checkLincheckOutput(outputFileName)
}

/**
 * Checks that spin-cycle repeated events are shortened
 * when the reason of a failure is not deadlock or obstruction freedom violation (incorrect results failure)
 */
class SpinlockInIncorrectResultsWithClocksTest {

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
        .checkLincheckOutput("spin_lock_in_incorrect_results_failure.txt")


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
            emptyList(),
            listOf(
                listOf(
                    Actor(method = SpinlockInIncorrectResultsWithClocksTest::a.javaMethod!!, arguments = emptyList()),
                    Actor(method = SpinlockInIncorrectResultsWithClocksTest::b.javaMethod!!, arguments = emptyList())
                ),
                listOf(
                    Actor(method = SpinlockInIncorrectResultsWithClocksTest::c.javaMethod!!, arguments = emptyList()),
                    Actor(method = SpinlockInIncorrectResultsWithClocksTest::d.javaMethod!!, arguments = emptyList())
                )
            ),
            emptyList()
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
 * Checks spin cycle start label is placed correctly when many nested calls are before and inside spin cycle.
 */
class ManyNestedFunctionsBeforeAndInsideSpinCycleTest {

    private val counter = AtomicInteger(0)
    private val sharedData = AtomicInteger(0)

    @Operation
    fun causesSpinLock() {
        counter.incrementAndGet()
        counter.decrementAndGet()
    }

    @Operation
    fun spinLockOperation() {
        if (counter.get() != 0) {
            a()
        }
    }

    private fun a() = b()

    private fun b() = c()

    private fun c() {
        while (true) {
            d()
        }
    }

    private fun d() = e()

    private fun e() {
        sharedData.compareAndSet(2, 1)
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(ManyNestedFunctionsBeforeAndInsideSpinCycleTest::causesSpinLock) }
                thread { actor(ManyNestedFunctionsBeforeAndInsideSpinCycleTest::spinLockOperation) }
            }
        }
        .minimizeFailedScenario(false)
        .checkImpl(this::class.java)
        .checkLincheckOutput("nested_calls_spin_lock.txt")

}

/**
 * This test is created to verify this [bug](https://github.com/JetBrains/lincheck/issues/218) is resolved.
 * Checks that if spin cycle starts right after the method call, then spin cycle start event will be placed correctly.
 */
class SpinCycleFirstExecutionIsFirstInMethodCallTest {

    val counter = AtomicInteger(0)
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
        do {
            val value = getSharedVariable()
            someUselessSharedState.compareAndSet(value, !value)
        } while (true)
    }

    private fun getSharedVariable(): Boolean = someUselessSharedState.get()


    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(SpinCycleFirstExecutionIsFirstInMethodCallTest::trigger) }
                thread { actor(SpinCycleFirstExecutionIsFirstInMethodCallTest::causesSpinLock) }
            }
        }
        .minimizeFailedScenario(false)
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_cycle_with_zero_invocations_before.txt")
}

/**
 * Checks that a correct spin cycle period is found when two calls inside a spin cycle differ only by their receivers.
 */
class SpinCycleWithPeriodTwiceBiggerBySwitchingReceiversTest {

    private val counter = AtomicInteger(0)
    private val sharedData = AtomicInteger(0)

    @Operation
    fun spinLockCause() {
        counter.incrementAndGet()
        counter.decrementAndGet()
    }

    @Operation
    fun spinLock() {
        if (counter.get() != 0) {
            spinLockActions()
        }
    }


    private fun spinLockActions() {
        val descriptors = Array(2) { Descriptor() }
        while (true) {
            repeat(2) {
                descriptors[it].check()
            }
        }
    }

    inner class Descriptor {
        fun check() {
            sharedData.compareAndSet(2, 1)
        }
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(SpinCycleWithPeriodTwiceBiggerBySwitchingReceiversTest::spinLockCause) }
                thread { actor(SpinCycleWithPeriodTwiceBiggerBySwitchingReceiversTest::spinLock) }
            }
        }
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_cycle_twice_bigger_because_of_switching_receivers.txt")
}

/**
 * Checks that a correct spin cycle period is found when two calls inside a spin cycle differ only by their parameters.
 */
class SpinCycleWithPeriodTwiceBiggerBySwitchingParametersTest {

    private val counter = AtomicInteger(0)
    private val sharedData = AtomicInteger(0)

    @Operation
    fun spinLockCause() {
        counter.incrementAndGet()
        counter.decrementAndGet()
    }

    @Operation
    fun spinLock() {
        if (counter.get() != 0) {
            spinLockActions()
        }
    }


    private fun spinLockActions() {
        while (true) {
            repeat(2) { someCall(it) }
        }
    }

    private fun someCall(number: Int) {
        sharedData.compareAndSet(3, number)
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(SpinCycleWithPeriodTwiceBiggerBySwitchingParametersTest::spinLockCause) }
                thread { actor(SpinCycleWithPeriodTwiceBiggerBySwitchingParametersTest::spinLock) }
            }
        }
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_cycle_twice_bigger_because_of_switching_parameters.txt")
}

/**
 * Verify that if spin cycle can't be found by considering switch points and method parameters,
 * Lincheck should try to find it without considering parameters.
 */
class SpinLockPeriodCanNotBeFoundOnlyWithParametersTest {
    private val counter = AtomicInteger(0)
    private val sharedData = AtomicInteger(0)

    @Operation
    fun spinLockCause() {
        counter.incrementAndGet()
        counter.decrementAndGet()
    }

    @Operation
    fun spinLock() {
        if (counter.get() != 0) {
            spinLockActions()
        }
    }


    private fun spinLockActions() {
        var counter = 0
        while (true) {
            someCall(counter++)
        }
    }

    private fun someCall(number: Int) {
        sharedData.compareAndSet(3, number)
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(SpinLockPeriodCanNotBeFoundOnlyWithParametersTest::spinLockCause) }
                thread { actor(SpinLockPeriodCanNotBeFoundOnlyWithParametersTest::spinLock) }
            }
        }
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_cycle_with_different_parameters.txt")
}


/**
 * Checks that a correct spin cycle period is found when two atomic calls inside a spin cycle differ only by their parameters.
 */
class SpinCycleWithPeriodTwiceBiggerBySwitchingAtomicMethodParametersTest {

    private val counter = AtomicInteger(0)
    private val sharedData = AtomicInteger(0)

    @Operation
    fun spinLockCause() {
        counter.incrementAndGet()
        counter.decrementAndGet()
    }

    @Operation
    fun spinLock() {
        if (counter.get() != 0) {
            spinLockActions()
        }
    }


    private fun spinLockActions() {
        while (true) {
            repeat(2) { sharedData.compareAndSet(3, it) }
        }
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(SpinCycleWithPeriodTwiceBiggerBySwitchingAtomicMethodParametersTest::spinLockCause) }
                thread { actor(SpinCycleWithPeriodTwiceBiggerBySwitchingAtomicMethodParametersTest::spinLock) }
            }
        }
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_cycle_twice_bigger_because_of_atomic_method_parameters.txt")
}
