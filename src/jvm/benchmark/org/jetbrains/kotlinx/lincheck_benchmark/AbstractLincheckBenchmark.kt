/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package org.jetbrains.kotlinx.lincheck_benchmark

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import kotlin.reflect.KClass
import org.junit.Test


abstract class AbstractLincheckBenchmark(
    private vararg val expectedFailures: KClass<out LincheckFailure>
) {

    @Test(timeout = TIMEOUT)
    fun benchmarkWithStressStrategy(): Unit = LincheckOptions {
        this as LincheckOptionsImpl
        mode = LincheckMode.Stress
        configure()
    }.runTest()

    @Test(timeout = TIMEOUT)
    fun benchmarkWithModelCheckingStrategy(): Unit = LincheckOptions {
        this as LincheckOptionsImpl
        mode = LincheckMode.ModelChecking
        configure()
    }.runTest()

    private fun LincheckOptions.runTest() {
        check(this is LincheckOptionsImpl)
        val statisticsTracker = StatisticsTracker(
            granularity = StatisticsGranularity.PER_INVOCATION
        )
        val failure = runTests(this@AbstractLincheckBenchmark::class.java, tracker = statisticsTracker)
        if (failure == null) {
            assert(expectedFailures.isEmpty()) {
                "This test should fail, but no error has been occurred (see the logs for details)"
            }
        } else {
            assert(expectedFailures.contains(failure::class)) {
                "This test has failed with an unexpected error: \n $failure"
            }
        }
        val statistics = statisticsTracker.toBenchmarkStatistics(
            name = (this@AbstractLincheckBenchmark::class.java).simpleName,
            mode = this.mode,
        )
        benchmarksReporter.registerBenchmark(statistics)
    }

    private fun LincheckOptionsImpl.configure() {
        maxThreads = 4
        maxOperationsInThread = 3
        minimizeFailedScenario = false
        iterations = 30
        invocationsPerIteration = 5000
        customize()
    }

    internal open fun LincheckOptions.customize() {}

}

private const val TIMEOUT = 5 * 60 * 1000L // 5 minutes