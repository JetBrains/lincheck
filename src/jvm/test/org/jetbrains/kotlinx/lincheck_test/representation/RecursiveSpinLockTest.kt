/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.lincheck.util.isInTraceDebuggerMode
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Checks proper output in case of recursive spin-lock in one thread.
 */
class RecursiveSpinLockTest {
    @Before
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
            deadSpinCycleRecursive()
        }
    }

    private fun deadSpinCycleRecursive() {
        repeat(4) {
            val value = getSharedVariable()
            someUselessSharedState.compareAndSet(value, !value)
        }
        deadSpinCycleRecursive()
    }

    private fun getSharedVariable(): Boolean = someUselessSharedState.get()

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
        .checkLincheckOutput("spin_lock/recursive_spin_lock")

}

/**
 * Checks proper output in case of recursive spin-lock in one thread.
 * Spin lock should be twice bigger because of flipping parameters of the method.
 */
class RecursiveSpinWithParamsLockTest {
    @Before
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val counter = AtomicInteger(0)
    private val someUselessSharedState = AtomicBoolean(false)
    private val flag = AtomicBoolean(false)

    @Operation
    fun trigger() {
        counter.incrementAndGet()
        counter.decrementAndGet()
    }

    @Operation
    fun causesSpinLock() {
        if (counter.get() != 0) {
            deadSpinCycleRecursive(true)
        }
    }

    private fun deadSpinCycleRecursive(value: Boolean) {
        flag.set(value)
        repeat(4) {
            val sharedStateValue = getSharedVariable()
            someUselessSharedState.compareAndSet(sharedStateValue, !sharedStateValue)
        }
        deadSpinCycleRecursive(!value)
    }

    private fun getSharedVariable(): Boolean = someUselessSharedState.get()

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
        .checkLincheckOutput("spin_lock/recursive_spin_lock_params")

}

/**
 * Checks proper output in case of recursive spin-lock in one thread.
 * Should correctly detect spin cycle and place spin cycle label in case
 * when all potential switch points are nested in non-atomic methods.
 */
class RecursiveSpinLockWithInnerEventsTest {
    @Before
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
            deadSpinCycleRecursive()
        }
    }

    private fun deadSpinCycleRecursive() {
        repeat(4) {
            val value = getSharedVariable()
            action(value)
        }
        deadSpinCycleRecursive()
    }

    private fun action(value: Boolean) = someUselessSharedState.compareAndSet(value, !value)

    private fun getSharedVariable(): Boolean = someUselessSharedState.get()

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(RecursiveSpinLockWithInnerEventsTest::trigger) }
                thread { actor(RecursiveSpinLockWithInnerEventsTest::causesSpinLock) }
            }
        }
        .minimizeFailedScenario(false)
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_lock/recursive_spin_cycle_inner_events")

}

/**
 * Checks proper output in case of recursive spin-lock in one thread.
 * Should correctly detect spin cycle and place the spin cycle labels when
 * the recursion includes two different method calls.
 */
class RecursiveSpinLockTwoStepRecursionEventsTest {
    @Before
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
            outerRecursiveSpinCycle()
        }
    }

    private fun outerRecursiveSpinCycle() {
        deadSpinCycleRecursive()
    }

    private fun deadSpinCycleRecursive() {
        repeat(4) {
            val value = getSharedVariable()
            action(value)
        }
        outerRecursiveSpinCycle()
    }

    private fun action(value: Boolean) = someUselessSharedState.compareAndSet(value, !value)

    private fun getSharedVariable(): Boolean = someUselessSharedState.get()

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(RecursiveSpinLockTwoStepRecursionEventsTest::trigger) }
                thread { actor(RecursiveSpinLockTwoStepRecursionEventsTest::causesSpinLock) }
            }
        }
        .minimizeFailedScenario(false)
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_lock/recursive_spin_cycle_two_step")

}

/**
 * Checks proper output in case of recursive spin-lock in two threads.
 */
class RecursiveTwoThreadsSpinLockTest {
    @Before
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val sharedState1 = AtomicBoolean(false)
    private val sharedState2 = AtomicBoolean(false)

    @Operation
    fun one(): Int {
        meaninglessActions2()
        meaninglessActions1()

        sharedState1.set(false)
        sharedState2.set(false)

        return 1
    }

    @Operation
    fun two(): Int {
        meaninglessActions1()
        meaninglessActions2()

        sharedState2.set(false)
        sharedState1.set(false)

        return 2
    }

    private fun meaninglessActions1() {
        if (!sharedState2.compareAndSet(false, true)) {
            meaninglessActions1()
        }
    }
    private fun meaninglessActions2() {
        if (!sharedState1.compareAndSet(false, true)) {
            meaninglessActions2()
        }
    }

    @Test
    fun testWithModelCheckingStrategy() = ModelCheckingOptions()
        .minimizeFailedScenario(true)
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_lock/recursive_two_threads_spin_lock")
}


/**
 * Checks proper output in case of recursive spin-lock in one thread.
 * Should correctly detect spin cycle and place spin cycle label in case
 * when the last event before the cycle is located in the same method call but with
 * different input parameters.
 */
class RecursiveParametersDependentSpinLockTest {
    @Before
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val value = AtomicBoolean(false)

    @Operation
    fun actorMethod() {
        b(recursive = false)
        b(recursive = true)
    }


    fun b(recursive: Boolean) {
        value.compareAndSet(false, true) // point X
        c()
        if (recursive) {
            b(recursive = true)
        }
    }

    fun c() = value.get()

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario { parallel { thread { actor(::actorMethod) } } }
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_lock/recursive_spin_lock_param_dependent")
}

class RecursiveSpinLockRepeatsOperationTest {
    private val value = AtomicBoolean(false)

    fun foo() {
        if (value.get()) return
        foo()
    }

    @Test
    fun test() = ModelCheckingOptions()
        .iterations(0)
        .addCustomScenario { parallel { thread { actor(::foo) } } }
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_lock/recursive_spin_lock_repeats_operation.txt")
}
