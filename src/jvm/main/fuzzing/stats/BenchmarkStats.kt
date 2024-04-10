/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package fuzzing.stats

import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.io.path.Path

class BenchmarkStats(
    var type: TestType? = null,

    // test setup info
    var iterations: Int = 0,
    var invocationsPerIteration: Int = 0,
    var threads: Int = 0,
    var actorsPerThread: Int = 0,
    var initActors: Int = 0,
    var postActors: Int = 0,

    // execution time
    var totalDurationMs: Long = 0,

    // total coverage existing
//    var totalDetectedCoverageBranches: Long = 0,
//    var totalDetectedCoverageLines: Long = 0,

    // coverage metric per iteration
    val failedIterations: MutableList<Int> = mutableListOf(),
    val totalFoundCoverage: MutableList<Int> = mutableListOf(),
    val iterationFoundCoverage: MutableList<Int> = mutableListOf(),
    val maxIterationFoundCoverage: MutableList<Int> = mutableListOf(),

    // for fuzzer only
    val savedInputsCounts: MutableList<Int> = mutableListOf(),
) {
    override fun toString(): String {
        return (
            "BenchmarkStats( \n" +
                "\titerations=$iterations \n" +
                "\tinvocationsPerIteration=$invocationsPerIteration \n" +
                "\tthreads=$threads \n" +
                "\tactorsPerThread=$actorsPerThread \n" +
                "\tinitActors=$initActors \n" +
                "\tpostActors=$postActors \n" +
                "\ttotalDuration=${totalDurationMs / 1000 / 60}m ${(totalDurationMs / 1000) % 60}s ${totalDurationMs % 1000}ms \n" +
                "\ttotalFoundCoverage=${totalFoundCoverage.size} \n" +
                "\titerationFoundCoverage=${iterationFoundCoverage.size} \n" +
                "\tmaxIterationFoundCoverage=${maxIterationFoundCoverage.size} \n" +
                "\tsavedInputsCount=${savedInputsCounts.size} \n" +
                "\tfailedIterations=${failedIterations.size} \n" +
            ") \n" +
            "Coverage: edges=${if (totalFoundCoverage.isNotEmpty()) totalFoundCoverage.last() else 0}"
        )
    }
}

enum class TestType {
    MODEL_CHECKING,
    FUZZING
}

class BenchmarkCollector {
    private val tests: MutableMap<String, MutableList<BenchmarkStats>> = mutableMapOf()

    fun add(testName: String, stats: BenchmarkStats) {
        if (!tests.contains(testName)) tests[testName] = mutableListOf()
        tests[testName]!!.add(stats)
    }

    fun plotEachAndClear() {
        val dir = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date())

        tests.forEach { (testName, results) ->
            // apart plotting
            results.forEach {
                val path = Path(System.getProperty("user.dir"), "/lets-plot-images/${testName}/single/$dir/")
                val file = File(path.toString())
                if (!file.exists()) file.mkdirs()

                plotSingle(
                    test = it,
                    filename = testName,
                    path = path.toString()
                )
            }

            // merged plotting
            val path = Path(System.getProperty("user.dir"), "/lets-plot-images/${testName}/merged/$dir/")
            val file = File(path.toString())
            if (!file.exists()) file.mkdirs()

            plotMerged(
                tests = results.map { it },
                filename = testName,
                path = path.toString()
            )
        }

        tests.clear()
    }
}

val benchmarkCollector = BenchmarkCollector()
