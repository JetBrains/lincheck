/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_benchmark

import org.junit.Before
import org.junit.AfterClass
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    ConcurrentHashMapBenchmark::class,
    ConcurrentSkipListMapBenchmark::class,
    // TODO: investigate performance problem in ConcurrentLinkedQueueBenchmark.benchmarkWithStressStrategy
    // ConcurrentLinkedQueueBenchmark::class,
    ConcurrentDequeBenchmark::class,
)
class LincheckBenchmarksSuite {

    @Before
    fun setUp() {
        System.gc()
    }

    companion object {
        @AfterClass
        @JvmStatic
        fun tearDown() {
            benchmarksReporter.saveReport()
        }
    }

}

class LincheckBenchmarksReporter {

    private val statistics = mutableMapOf<BenchmarkID, BenchmarkStatistics>()

    fun registerBenchmark(benchmarkStatistics: BenchmarkStatistics) {
        statistics[benchmarkStatistics.id] = benchmarkStatistics
    }

    fun saveReport() {
        val report = BenchmarksReport(statistics)
        report.saveJson("benchmarks-results")
        report.runtimePlot("running-time")
    }

}

val benchmarksReporter = LincheckBenchmarksReporter()