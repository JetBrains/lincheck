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
import org.jetbrains.letsPlot.scale.*
import org.jetbrains.letsPlot.sampling.samplingNone
import org.jetbrains.letsPlot.pos.positionDodge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit
import java.io.File


fun BenchmarksReport.runtimePlot(filename: String) {
    val data = runningTimeData()
    var plot = letsPlot(data)
    plot += ggtitle("Benchmarks running time")
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
        fill = "strategy"
    }
    ggsave(plot, "${filename}.html")
}

fun BenchmarksReport.invocationTimeByScenarioSizePlot(filename: String, benchmarkName: String) {
    val data = invocationTimeByScenarioSizeData(benchmarkName)
    var plot = letsPlot(data) {
        x = "params"
    }
    plot += ggtitle("Invocation average time by scenario size", subtitle = benchmarkName)
    plot += ggsize(600, 800)
    plot += labs(
        x = "(#threads, #operations)",
        y = "time (us)",
    )
    plot += geomBar(
        stat = Stat.identity,
        position = positionDodge(),
    ) {
        y = "timeAverage"
        fill = "strategy"
    }
    plot += geomErrorBar(
        width = .1,
        position = positionDodge(0.9),
    ) {
        ymin = "timeErrorLow"
        ymax = "timeErrorHigh"
        group = "strategy"
    }
    ggsave(plot, "${filename}.html")
}

fun BenchmarksReport.invocationsTimePlot(filename: String, benchmarkID: BenchmarkID) {
    val data = invocationsTimeData(benchmarkID)
    var plot = letsPlot(data)
    plot += ggtitle("Invocations time", subtitle = benchmarkID)
    plot += labs(
        x = "# invocation",
        y = "time (us)"
    )
    plot += ggsize(1600, 900)
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

fun BenchmarksReport.runningTimeData(
    durationUnit: DurationUnit = DurationUnit.MILLISECONDS
): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val ids = data.keys.toList()
    map["name"] = ids.map { id ->
        data[id]!!.className
    }
    map["strategy"] = ids.map { id ->
        data[id]!!.strategy.toString()
    }
    map["runningTime"] = ids.map { id ->
        data[id]!!.runningTimeNano.nanoseconds.toLong(durationUnit)
    }
    return map
}

fun BenchmarksReport.invocationTimeByScenarioSizeData(benchmarkName: String,
      durationUnit: DurationUnit = DurationUnit.MICROSECONDS,
): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val benchmarks = data.values
        .filter { it.className == benchmarkName }
        .map { (it.strategy to it.scenariosStatistics) }
    map["params"] = benchmarks.flatMap { (_, stats) ->
        stats.map { (it.threads to it.operations).toString() }
    }
    map["strategy"] = benchmarks.flatMap { (strategy, stats) ->
        stats.map { strategy.toString() }
    }
    map["timeAverage"] = benchmarks.flatMap { (_, stats) ->
        stats.map { it.invocationAverageTimeNano.nanoseconds.toLong(durationUnit) }
    }
    map["timeErrorLow"] = benchmarks.flatMap { (_, stats) ->
        stats.map {
            (it.invocationAverageTimeNano - it.invocationStandardErrorTimeNano)
                .nanoseconds.toLong(durationUnit)
        }
    }
    map["timeErrorHigh"] = benchmarks.flatMap { (_, stats) ->
        stats.map {
            (it.invocationAverageTimeNano + it.invocationStandardErrorTimeNano)
                .nanoseconds.toLong(durationUnit)
        }
    }
    return map
}

fun BenchmarksReport.invocationsTimeData(benchmarkID: BenchmarkID,
     durationUnit: DurationUnit = DurationUnit.MICROSECONDS,
): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val invocationsRunningTimeNano = data[benchmarkID]!!.invocationsRunningTimeNano
        .apply { convertTo(durationUnit) }
    map["invocationID"] = invocationsRunningTimeNano.indices.toList()
    map["invocationRunningTimeNano"] = invocationsRunningTimeNano
    return map
}

private fun LongArray.convertTo(unit: DurationUnit) {
    for (i in indices) {
        this[i] = this[i].nanoseconds.toLong(unit)
    }
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
    report.runtimePlot("running-time-bar-plot")
    for (name in report.benchmarkNames) {
        report.invocationTimeByScenarioSizePlot("$name-invocation-time-by-size-bar-plot", name)
    }
    for (id in report.benchmarkIDs) {
        report.invocationsTimePlot("$id-invocations-time-scatter-plot", id)
    }
}