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

/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */


import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.atomicArrayOfNulls
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Param(name = "element", gen = IntGen::class, conf = "0:3")
class FaaQueueSpinLockTest {

    private val queue = FlatCombiningQueue<Int>()

    @Operation
    fun enqueue(@Param(name = "element") element: Int) = queue.enqueue(element)

    @Operation
    fun dequeue() = queue.dequeue()

    @Test
    fun modelCheckingTest() {
        ModelCheckingOptions()
            .addCustomScenario {
                initial {
                    actor(FaaQueueSpinLockTest::enqueue, 1)
                    actor(FaaQueueSpinLockTest::enqueue, 1)
                }
                parallel {
                    thread {
                        actor(FaaQueueSpinLockTest::enqueue, 1)
                        actor(FaaQueueSpinLockTest::dequeue)
                    }
                    thread {
                        actor(FaaQueueSpinLockTest::dequeue)
                        actor(FaaQueueSpinLockTest::dequeue)
                    }
                    thread {
                        actor(FaaQueueSpinLockTest::dequeue)
                        actor(FaaQueueSpinLockTest::enqueue, 1)
                    }
                }
                post {
                    actor(FaaQueueSpinLockTest::enqueue, 1)
                    actor(FaaQueueSpinLockTest::dequeue)
                }
            }
            .minimizeFailedScenario(false)
            .sequentialSpecification(FaaIntQueueSequential::class.java)
            .checkImpl(this::class.java)
            .checkLincheckOutput("faa_queue.txt")
    }

}

class FlatCombiningQueue<E> {
    private val queue = ArrayDeque<E>() // sequential queue
    private val combinerLock = atomic(false) // unlocked initially
    private val tasksForCombiner = atomicArrayOfNulls<Any?>(TASKS_FOR_COMBINER_SIZE)

    private fun tryLock() = combinerLock.compareAndSet(false, true)
    private fun unlock() = check(combinerLock.compareAndSet(true, false))

    fun enqueue(element: E) {
        var index = randomCellIndex() // code locations: 59, 60, 61
        while (true) {
            if (tasksForCombiner[index].compareAndSet(null, element)) break // code locations: 17, 18
            index = randomCellIndex() // code locations: 59, 60, 61
        }

        while (!tryLock()) {
            val value = tasksForCombiner[index].value
            if (value is Result<*>) {
                tasksForCombiner[index].value = null
                return
            }
        }
        help()
        unlock()
        tasksForCombiner[index].value as Result<*>
        tasksForCombiner[index].value = null
        return
    }

    fun dequeue(): E? {
        var index = randomCellIndex()
        while (true) {
            if (tasksForCombiner[index].compareAndSet(null, Dequeue)) break
            index = randomCellIndex()
        }

        while (!tryLock()) {
            val value = tasksForCombiner[index].value
            if (value is Result<*>) {
                tasksForCombiner[index].value = null
                return value.value as? E
            }
        }
        help()
        unlock()

        val value = tasksForCombiner[index].value as Result<*>
//         Uncomment the line below to get correct implementation
//        tasksForCombiner[index].value = null
        return value.value as? E
    }


    private fun help() {
        repeat(TASKS_FOR_COMBINER_SIZE) {
            val task = tasksForCombiner[it].value ?: return@repeat
            if (task is Result<*>) return@repeat
            if (task === Dequeue) {
                tasksForCombiner[it].value = Result(queue.removeFirstOrNull())
            } else {
                queue.add(task as E)
                tasksForCombiner[it].value = Result(Unit)
            }
        }
    }

    private fun randomCellIndex(): Int =
        ThreadLocalRandom.current().nextInt(tasksForCombiner.size)
}

private const val TASKS_FOR_COMBINER_SIZE = 3 // Do not change this constant!

private object Dequeue

private class Result<V>(
    val value: V
)

class FaaIntQueueSequential {
    private val q = ArrayList<Int>()

    fun enqueue(element: Int) {
        q.add(element)
    }

    fun dequeue() = q.removeFirstOrNull()
    fun remove(element: Int) = q.remove(element)
}


/**
 * Checks that if spin cycle starts right after method call then spin cycle start event will be placed correctly.
 */
class SpinCycleLastExecutionIsFirstInMethodCallBeforeCycleTest {

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
        var value = getSharedVariable()
        while (true) {
            someUselessSharedState.compareAndSet(value, !value)
            value = getSharedVariable()
        }
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
        .checkLincheckOutput("spin_cycle_with_invocation_before_is_last_cycle_event.txt")
}


/**
 * Checks that if spin cycle starts right after method call then spin cycle start event will be placed correctly.
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

class RecursiveSpiLockTest {

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
                thread { actor(SpinCycleFirstExecutionIsFirstInMethodCallTest::trigger) }
                thread { actor(SpinCycleFirstExecutionIsFirstInMethodCallTest::causesSpinLock) }
            }
        }
        .minimizeFailedScenario(false)
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_lock_recursion_single_thread.txt")

}