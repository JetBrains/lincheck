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

import org.jetbrains.letsPlot.*
import org.jetbrains.letsPlot.export.*
import org.jetbrains.letsPlot.geom.*
import org.jetbrains.letsPlot.label.*
import org.jetbrains.letsPlot.pos.positionDodge
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.jetbrains.letsPlot.sampling.samplingNone
import org.jetbrains.letsPlot.scale.scaleYContinuous
import org.jetbrains.letsPlot.scale.scaleYLog10
import java.io.File


fun BenchmarksReport.runtimePlot(filename: String) {
    val data = runningTimeData()
    var plot = letsPlot(data)
    plot += ggsize(600, 800)
    plot += labs(
        x = "benchmark name",
        y = "time (ms)",
    )
    plot += geomBar(
        stat = Stat.identity,
        position = positionDodge(),
    ) {
        x = "name"
        y = "runningTime"
        fill = "mode"
    }
    ggsave(plot, "${filename}.html")
}

fun BenchmarksReport.invocationsTimePlot(filename: String, benchmarkID: BenchmarkID) {
    val data = invocationsTimeData(benchmarkID)
    var plot = letsPlot(data)
    plot += ggsize(1600, 900)
    plot += labs(
        x = "# invocation",
        y = "time (us)"
    )
    plot += geomPoint(
        stat = Stat.identity,
        sampling = samplingNone,
    ) {
        x = "invocationID"
        y = "invocationRunningTimeNano"
    }
    plot += scaleYLog10()
    plot += scaleYContinuous(
        breaks = listOf(10, 100, 1000, 10_000, 100_000, 1_000_000),
        limits = 10 to 5_000_000
    )
    ggsave(plot, "${filename}.html")
}

fun BenchmarksReport.runningTimeData(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val ids = data.keys.toList()
    map["name"] = ids.map { id ->
        data[id]!!.name
    }
    map["mode"] = ids.map { id ->
        data[id]!!.mode.toString()
    }
    map["runningTime"] = ids.map { id ->
        data[id]!!.runningTimeNano.nanoseconds.inWholeMilliseconds
    }
    return map
}

fun BenchmarksReport.invocationsTimeData(benchmarkID: BenchmarkID): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val invocationsRunningTimeNano = data[benchmarkID]!!
        // TODO: move preprocessing into [toBenchmarkStatistics] function
        .scenariosStatistics.flatMap { statistics ->
            statistics.invocationsRunningTimeNano
                .map { it.nanoseconds.inWholeMicroseconds }
                .asIterable()
        }
    map["invocationID"] = invocationsRunningTimeNano.indices.toList()
    map["invocationRunningTimeNano"] = invocationsRunningTimeNano
    return map
}

fun main(args: Array<String>) {
    val reportFilename = args.getOrNull(0)
    if (reportFilename == null) {
        println("Please specify path to a file containing benchmarks report!")
        return
    }
    val file = File("$reportFilename")
    val report = file.inputStream().use { inputStream ->
        Json.decodeFromStream<BenchmarksReport>(inputStream)
    }
    report.runtimePlot("running-time")
    report.invocationsTimePlot("invocations-time", "ConcurrentHashMap-ModelChecking")
}