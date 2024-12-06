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


import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.scenario
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import org.jetbrains.kotlinx.lincheck_test.gpmc.*
import org.jetbrains.kotlinx.lincheck_test.util.*
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import kotlin.concurrent.thread
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import org.junit.Test

class CustomThreadsRepresentationTest {

    @Volatile
    @JvmField
    var value: Int = 0

    fun basic() {
        val block = {
            value += 1
            valueUpdater.getAndIncrement(this)
            unsafe.getAndAddInt(this, valueFieldOffset, 1)
            synchronized(this) {
                value += 1
            }
        }
        val threads = List(3) { thread { block() } }
        threads.forEach { it.join() }
        check(false) // to trigger failure and trace collection
    }

    @Test(timeout = TIMEOUT)
    fun basicTest() = modelCheckerTraceTest(
        testClass = this::class,
        testOperation = this::basic,
        invocations = 1_000,
        outputFileName = if (isJdk8) "custom_threads_trace_jdk8.txt" else "custom_threads_trace.txt",
    )

    fun livelock(): Int {
        var counter = 0
        val lock1 = SpinLock()
        val lock2 = SpinLock()
        val t1 = thread {
            lock1.withLock {
                lock2.withLock {
                    counter++
                }
            }
        }
        val t2 = thread {
            lock2.withLock {
                lock1.withLock {
                    counter++
                }
            }
        }
        t1.join()
        t2.join()
        return counter
    }

    @Test(timeout = TIMEOUT)
    fun livelockTest() = modelCheckerTraceTest(
        testClass = this::class,
        testOperation = this::livelock,
        invocations = 1_000,
        outputFileName = if (isJdk8) "custom_threads_livelock_trace_jdk8.txt" else "custom_threads_livelock_trace.txt",
    )

    @Suppress("DEPRECATION") // Unsafe
    companion object {
        val unsafe =
            UnsafeHolder.UNSAFE

        private val valueField =
            CustomThreadsRepresentationTest::class.java.getDeclaredField("value")

        private val valueUpdater =
            AtomicIntegerFieldUpdater.newUpdater(CustomThreadsRepresentationTest::class.java, "value")

        private val valueFieldOffset =
            unsafe.objectFieldOffset(valueField)
    }

}

internal fun modelCheckerTraceTest(
    testClass: KClass<*>,
    testOperation: KFunction<*>,
    outputFileName: String,
    invocations: Int = DEFAULT_INVOCATIONS_COUNT,
) {
    val scenario = scenario {
        parallel { thread { actor(testOperation) } }
    }
    val verifier = ExceptionFailingVerifier()
    withLincheckJavaAgent(InstrumentationMode.MODEL_CHECKING) {
        val strategy = createStrategy(testClass.java, scenario)
        val failure = strategy.runIteration(invocations, verifier)
        assert(failure != null)
        failure.checkLincheckOutput(outputFileName)
    }
}

private fun createStrategy(testClass: Class<*>, scenario: ExecutionScenario): ModelCheckingStrategy {
    return createConfiguration(testClass)
        .createStrategy(
            testClass = testClass,
            scenario = scenario,
            validationFunction = null,
            stateRepresentationMethod = null,
        ) as ModelCheckingStrategy
}

private fun createConfiguration(testClass: Class<*>) =
    ModelCheckingOptions()
        .invocationTimeout(5_000) // 5 sec
        .createTestConfigurations(testClass)


private class ExceptionFailingVerifier : Verifier {
    override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
        return when (results!!.parallelResults[0][0]!!) {
            is ExceptionResult -> false
            else -> true
        }
    }
}

private const val DEFAULT_INVOCATIONS_COUNT = 100

private const val TIMEOUT = 30_000L // 30 sec