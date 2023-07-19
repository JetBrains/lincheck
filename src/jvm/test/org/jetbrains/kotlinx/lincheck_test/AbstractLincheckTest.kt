/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.*
import kotlin.math.*
import kotlin.reflect.*
import org.jetbrains.kotlinx.lincheck_test.util.*

abstract class AbstractLincheckTest(
    private vararg val expectedFailures: KClass<out LincheckFailure>,
) : VerifierState() {

    @Test(timeout = TIMEOUT)
    fun testWithStressStrategy(): Unit = LincheckOptions {
        this as LincheckOptionsImpl
        mode = LincheckMode.Stress
        configure()
    }.runTest()

    @Test(timeout = TIMEOUT)
    fun testWithModelCheckingStrategy(): Unit = LincheckOptions {
        this as LincheckOptionsImpl
        mode = LincheckMode.ModelChecking
        configure()
    }.runTest()

    @Test(timeout = TIMEOUT)
    fun testWithHybridStrategy(): Unit = LincheckOptions {
        this as LincheckOptionsImpl
        mode = LincheckMode.Hybrid
        configure()
    }.runTest()

    private fun LincheckOptions.runTest() {
        val failure = runTests(this@AbstractLincheckTest::class.java, tracker = runTracker)
        if (failure == null) {
            assert(expectedFailures.isEmpty()) {
                "This test should fail, but no error has been occurred (see the logs for details)"
            }
            if (testPlanningConstraints) {
                checkAdaptivePlanningConstraints()
            }
        } else {
            failure.trace?.let { checkTraceHasNoLincheckEvents(it.toString()) }
            assert(expectedFailures.contains(failure::class)) {
                "This test has failed with an unexpected error: \n $failure"
            }
        }
    }

    private fun LincheckOptions.checkAdaptivePlanningConstraints() {
        this as LincheckOptionsImpl
        // if we tested only custom scenarios, then return
        if (!generateRandomScenarios)
            return
        // we expect test to run only custom or only random scenarios
        check(customScenariosOptions.size == 0)
        val randomTestingTimeNano = testingTimeInSeconds * 1_000_000_000
        val runningTimeNano = runStatistics.values.sumOf { it.totalRunningTimeNano }
        val timeDeltaNano = AdaptivePlanner.TIME_ERROR_MARGIN_NANO
        // check that the actual running time is close to specified time
        assert(abs(randomTestingTimeNano - runningTimeNano) < timeDeltaNano) { """
            Testing time is beyond expected bounds:
            actual: ${String.format("%.3f", runningTimeNano.toDouble() / 1_000_000_000)}
            expected: ${String.format("%.3f", randomTestingTimeNano.toDouble() / 1_000_000_000)}
        """.trimIndent()
        }
        // check invocations to iterations ratio (per each strategy run)
        for ((runName, statistics) in runStatistics.entries) {
            if (statistics.iterationsStatistics.isEmpty())
                return
            val invocationsRatio = statistics.averageInvocationsCount / statistics.iterationsCount
            val expectedRatio = AdaptivePlanner.INVOCATIONS_TO_ITERATIONS_RATIO.toDouble()
            val ratioError = 0.25
            assert(abs(invocationsRatio - expectedRatio) < expectedRatio * ratioError) { """
                Invocations to iterations ratio differs from expected.
                    actual: ${String.format("%.3f", invocationsRatio)}
                    expected: $expectedRatio
                    run name: $runName
            """.trimIndent()
            }
        }
    }

    private fun LincheckOptionsImpl.configure() {
        testingTimeInSeconds = 10
        maxThreads = 3
        maxOperationsInThread = 2
        minimizeFailedScenario = false
        customize()
    }

    internal open fun LincheckOptionsImpl.customize() {}

    internal open val testPlanningConstraints: Boolean = true

    override fun extractState(): Any = System.identityHashCode(this)

    private val runOptions = mutableMapOf<String, LincheckOptions>()
    private val runStatistics = mutableMapOf<String, Statistics>()

    private val runTracker = object : RunTracker {

        override fun runStart(name: String, options: LincheckOptions) {
            runOptions[name] = options
        }

        override fun runEnd(name: String, failure: LincheckFailure?, statistics: Statistics?) {
            check(statistics != null)
            runStatistics[name] = statistics
        }
    }

}

private const val TIMEOUT = 100_000L