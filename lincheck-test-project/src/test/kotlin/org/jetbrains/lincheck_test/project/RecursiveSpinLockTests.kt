package org.jetbrains.lincheck_test.project

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * Checks proper output in case of recursive spin-lock in one thread.
 * Should correctly detect spin cycle and place the spin cycle labels when
 * the recursion includes two different method calls.
 */
class RecursiveSpinLockTwoStepRecursionEventsTest {

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
        .check(this::class.java)

}