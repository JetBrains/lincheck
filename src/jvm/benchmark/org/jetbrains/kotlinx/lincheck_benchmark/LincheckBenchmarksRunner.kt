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

import org.junit.runner.Result
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runner.notification.*

class LincheckBenchmarksRunner(clazz: Class<*>) : BlockJUnit4ClassRunner(clazz) {

    override fun run(notifier: RunNotifier?) {
        super.run(notifier)
    }

}

class LincheckBenchmarksListener : RunListener() {

    private val statistics = mutableMapOf<String, BenchmarkStatistics>()

    fun registerBenchmarkStatistics(benchmarkName: String, benchmarkStatistics: BenchmarkStatistics) {
        statistics[benchmarkName] = benchmarkStatistics
    }

    override fun testRunFinished(result: Result?) {
        super.testRunFinished(result)
    }

}

val benchmarksListener = LincheckBenchmarksListener()