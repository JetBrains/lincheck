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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit
import java.io.File


typealias BenchmarkID = String

@Serializable
data class BenchmarksReport(
    val data: Map<String, BenchmarkStatistics>
)

@Serializable
data class BenchmarkStatistics(
    val name: String,
    val strategy: LincheckStrategy,
    val runningTimeNano: Long,
    val iterationsCount: Int,
    val invocationsCount: Int,
    val scenariosStatistics: List<ScenarioStatistics>,
    val invocationsRunningTimeNano: LongArray,
)

@Serializable
data class ScenarioStatistics(
    val threads: Int,
    val operations: Int,
    val invocationsCount: Int,
    val runningTimeNano: Long,
    val invocationAverageTimeNano: Long,
    val invocationStandardErrorTimeNano: Long,
)

val BenchmarksReport.benchmarkIDs: List<BenchmarkID>
    get() = data.keys.toList()

val BenchmarksReport.benchmarkNames: List<String>
    get() = data.map { (_, statistics) -> statistics.name }.distinct()

val BenchmarkStatistics.id: BenchmarkID
    get() = "$name-$strategy"

fun LincheckStatistics.toBenchmarkStatistics(name: String, strategy: LincheckStrategy) = BenchmarkStatistics(
    name = name,
    strategy = strategy,
    runningTimeNano = runningTimeNano,
    iterationsCount = iterationsCount,
    invocationsCount = invocationsCount,
    invocationsRunningTimeNano = iterationsStatistics
        .values.map { it.invocationsRunningTimeNano }
        .flatten(),
    scenariosStatistics = iterationsStatistics
        .values.groupBy { (it.scenario.nThreads to it.scenario.parallelExecution[0].size) }
        .map { (key, statistics) ->
            val (threads, operations) = key
            val invocationsRunningTime = statistics
                .map { it.invocationsRunningTimeNano }
                .flatten()
            val invocationsCount = statistics.sumOf { it.invocationsCount }
            val runningTimeNano = statistics.sumOf { it.runningTimeNano }
            val invocationAverageTimeNano = when {
                // handle the case when per-invocation statistics is not gathered
                invocationsRunningTime.isEmpty() -> (runningTimeNano.toDouble() / invocationsCount).toLong()
                else -> invocationsRunningTime.average().toLong()
            }
            val invocationStandardErrorTimeNano = when {
                // if per-invocation statistics is not gathered we cannot compute standard error
                invocationsRunningTime.isEmpty() -> -1L
                else -> invocationsRunningTime.standardError().toLong()
            }
            ScenarioStatistics(
                threads = threads,
                operations = operations,
                invocationsCount = invocationsCount,
                runningTimeNano = runningTimeNano,
                invocationAverageTimeNano = invocationAverageTimeNano,
                invocationStandardErrorTimeNano = invocationStandardErrorTimeNano,
            )
        }
)

fun BenchmarksReport.saveJson(filename: String) {
    val file = File("$filename.json")
    file.outputStream().use { outputStream ->
        Json.encodeToStream(this, outputStream)
    }
}

// saves the report in simple text format for testing integration with ij-perf dashboards
fun BenchmarksReport.saveTxt(filename: String) {
    val text = StringBuilder().apply {
        appendReportHeader()
        for (benchmarkStatistics in data.values) {
            // for ij-perf reports, we currently track only benchmarks overall running time
            appendBenchmarkRunningTime(benchmarkStatistics)
        }
    }.toString()
    val file = File("$filename.txt")
    file.writeText(text, charset = Charsets.US_ASCII)
}

private fun StringBuilder.appendReportHeader() {
    appendLine("Lincheck benchmarks suite")
}

private fun StringBuilder.appendBenchmarkRunningTime(benchmarkStatistics: BenchmarkStatistics) {
    with(benchmarkStatistics) {
        val runningTimeMs = runningTimeNano.nanoseconds.toLong(DurationUnit.MILLISECONDS)
        appendLine("${strategy}.${name}.runtime.ms $runningTimeMs")
    }
}