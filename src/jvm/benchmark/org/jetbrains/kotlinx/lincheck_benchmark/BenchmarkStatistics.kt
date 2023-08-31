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
import java.io.File

typealias BenchmarkID = String

@Serializable
data class BenchmarksReport(
    val data: Map<String, BenchmarkStatistics>
)

@Serializable
data class BenchmarkStatistics(
    val mode: LincheckMode,
    val name: String,
    val runningTimeNano: Long,
    val iterationsCount: Int,
    val invocationsCount: Int,
    val scenariosStatistics: List<ScenarioStatistics>,
)

@Serializable
data class ScenarioStatistics(
    val threads: Int,
    val operations: Int,
    val runningTimeNano: Long,
    val averageInvocationTimeNano: Long,
    val invocationsCount: Int,
    val warmUpInvocationsCount: Int,
    // TODO: check array is not empty
    val invocationsRunningTimeNano: LongArray,
)

val BenchmarksReport.benchmarkNames: List<String>
    get() = data.map { (_, statistics) -> statistics.name }.distinct()

fun BenchmarksReport.saveJson(filename: String) {
    val file = File("$filename.json")
    file.outputStream().use { outputStream ->
        Json.encodeToStream(this, outputStream)
    }
}

val BenchmarkStatistics.id: BenchmarkID
    get() = "$name-$mode"

fun Statistics.toBenchmarkStatistics(name: String, mode: LincheckMode) = BenchmarkStatistics(
    name = name,
    mode = mode,
    runningTimeNano = runningTimeNano,
    iterationsCount = iterationsCount,
    invocationsCount = invocationsCount,
    scenariosStatistics = iterationsStatistics.map { iterationStatistics ->
        // TODO: check that all scenarios either have or do not have init/post parts
        // TODO: check that in each scenario all threads have same number of operations
        val scenario = iterationStatistics.scenario
        ScenarioStatistics(
            threads = scenario.nThreads,
            operations = scenario.parallelExecution[0].size,
            runningTimeNano = iterationStatistics.runningTimeNano,
            averageInvocationTimeNano = iterationStatistics.averageInvocationTimeNano.toLong(),
            invocationsCount = iterationStatistics.invocationsCount,
            warmUpInvocationsCount = iterationStatistics.warmUpInvocationsCount,
            invocationsRunningTimeNano = iterationStatistics.invocationsRunningTimeNano,
        )
    }
)