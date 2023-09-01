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
import kotlin.math.*
import java.io.File
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit


typealias BenchmarkID = String

@Serializable
data class BenchmarksReport(
    val data: Map<String, BenchmarkStatistics>
)

@Serializable
data class BenchmarkStatistics(
    val mode: LincheckMode,
    val name: String,
    val runningTimeMilli: Long,
    val iterationsCount: Int,
    val invocationsCount: Int,
    val scenariosStatistics: List<ScenarioStatistics>,
    // TODO: check array is not empty
    val invocationsRunningTimeMicro: LongArray,
)

@Serializable
data class ScenarioStatistics(
    val threads: Int,
    val operations: Int,
    val invocationsCount: Int,
    val runningTimeMilli: Long,
    val invocationAverageTimeMicro: Long,
    val invocationStandardDeviationTimeMicro: Long,
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
    runningTimeMilli = runningTimeNano.nanoseconds.toLong(DurationUnit.MILLISECONDS),
    iterationsCount = iterationsCount,
    invocationsCount = invocationsCount,
    invocationsRunningTimeMicro = iterationsStatistics
        .map { it.invocationsRunningTimeNano }
        .flatten()
        .apply { convertTo(DurationUnit.MICROSECONDS) },
    scenariosStatistics = iterationsStatistics
        .groupBy { (it.scenario.nThreads to it.scenario.parallelExecution[0].size) }
        .map { (key, statistics) ->
            val (threads, operations) = key
            val invocationsRunningTime = statistics
                .map { it.invocationsRunningTimeNano.drop(100).toLongArray() }
                .flatten()
                .apply { convertTo(DurationUnit.MICROSECONDS) }
            ScenarioStatistics(
                threads = threads,
                operations = operations,
                invocationsCount = statistics.sumOf { it.invocationsCount },
                runningTimeMilli = statistics.sumOf {
                    it.runningTimeNano.nanoseconds.toLong(DurationUnit.MILLISECONDS)
                },
                invocationAverageTimeMicro = invocationsRunningTime.average().toLong(),
                invocationStandardDeviationTimeMicro = invocationsRunningTime.standardError().toLong(),
            )
        }
)

private fun LongArray.convertTo(unit: DurationUnit) {
    for (i in indices) {
        this[i] = this[i].nanoseconds.toLong(unit)
    }
}

fun Iterable<LongArray>.flatten(): LongArray {
    val size = sumOf { it.size }
    val result = LongArray(size)
    var i = 0
    for (array in this) {
        for (element in array) {
            result[i++] = element
        }
    }
    return result
}

fun LongArray.standardDeviation(): Double {
    val mean = round(average()).toLong()
    var variance = 0L
    for (x in this) {
        val d = x - mean
        variance += d * d
    }
    val std = sqrt(variance.toDouble() / (size - 1))
    println("avg=$mean")
    println("std=$std")
    return std
}

fun LongArray.standardError(): Double {
    return standardDeviation() / sqrt(size.toDouble())
}