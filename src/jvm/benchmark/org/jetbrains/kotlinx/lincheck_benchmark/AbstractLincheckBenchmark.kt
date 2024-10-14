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
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import kotlin.reflect.KClass
import org.junit.Test


abstract class AbstractLincheckBenchmark(
    private vararg val expectedFailures: KClass<out LincheckFailure>
) {

    @Test(timeout = TIMEOUT)
    fun benchmarkWithStressStrategy(): Unit = StressOptions().run {
        invocationsPerIteration(5_000)
        configure()
        runTest()
    }

    @Test(timeout = TIMEOUT)
    fun benchmarkWithModelCheckingStrategy(): Unit = ModelCheckingOptions().run {
        invocationsPerIteration(5_000)
        configure()
        runTest()
    }

    private fun <O : Options<O, *>> O.runTest() {
        val statisticsTracker = LincheckStatisticsTracker(
            granularity = benchmarksReporter.granularity
        )
        val klass = this@AbstractLincheckBenchmark::class
        val checker = LinChecker(klass.java, this)
        val failure = checker.checkImpl(customTracker = statisticsTracker)
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
            name = klass.simpleName!!.removeSuffix("Benchmark"),
            strategy = when (this) {
                is StressOptions -> LincheckStrategy.Stress
                is ModelCheckingOptions -> LincheckStrategy.ModelChecking
                else -> throw IllegalStateException("Unsupported Lincheck strategy")
            }
        )
        benchmarksReporter.registerBenchmark(statistics)
    }

    private fun <O : Options<O, *>> O.configure(): Unit = run {
        iterations(30)
        threads(3)
        actorsPerThread(2)
        actorsBefore(2)
        actorsAfter(2)
        minimizeFailedScenario(false)
        customize()
    }

    internal open fun <O: Options<O, *>> O.customize() {}

}

private const val TIMEOUT = 5 * 60 * 1000L // 5 minutes