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

import kotlinx.cli.*
import org.jetbrains.letsPlot.*
import org.jetbrains.letsPlot.export.*
import org.jetbrains.letsPlot.geom.*
import org.jetbrains.letsPlot.label.*
import org.jetbrains.letsPlot.scale.*
import org.jetbrains.letsPlot.sampling.samplingNone
import org.jetbrains.letsPlot.pos.positionDodge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.jetbrains.kotlinx.lincheck.LincheckStrategy
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit
import java.io.File
import java.nio.file.Paths


fun BenchmarksReport.runningTimeBarPlot(filename: String, path: String? = null) {
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
    ggsave(plot, "${filename}.html", path = path)
}

fun BenchmarksReport.runningTimeData(
    durationUnit: DurationUnit = DurationUnit.MILLISECONDS
): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val ids = data.keys.toList()
    map["name"] = ids.map { id ->
        data[id]!!.name
    }
    map["strategy"] = ids.map { id ->
        data[id]!!.strategy.toString()
    }
    map["runningTime"] = ids.map { id ->
        data[id]!!.runningTimeNano.nanoseconds.toLong(durationUnit)
    }
    return map
}

fun BenchmarksReport.invocationTimeByScenarioSizeBarPlot(
    benchmarkName: String,
    filename: String,
    path: String? = null)
{
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
    ggsave(plot, "${filename}.html", path = path)
}

fun BenchmarksReport.invocationTimeByScenarioSizeData(
    benchmarkName: String,
    durationUnit: DurationUnit = DurationUnit.MICROSECONDS,
): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val benchmarks = data.values
        .filter { it.name == benchmarkName }
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

fun BenchmarksReport.invocationTimeScatterPlot(
    benchmarkID: BenchmarkID,
    filename: String,
    path: String? = null
) {
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
    ggsave(plot, "${filename}.html", path = path)
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

enum class PlotType {
    AllPlots,
    RunningTimeBarPlot,
    InvocationTimeByScenarioSizeBarPlot,
    InvocationTimeScatterPlot,
}

const val defaultPlotExtension = "html"

fun PlotType.defaultOutputFilename(): String = when (this) {
    PlotType.RunningTimeBarPlot -> "running-time-bar-plot.$defaultPlotExtension"
    PlotType.InvocationTimeByScenarioSizeBarPlot -> "invocation-time-by-size-bar-plot.$defaultPlotExtension"
    PlotType.InvocationTimeScatterPlot -> "invocations-time-scatter-plot.$defaultPlotExtension"
    else -> throw IllegalArgumentException()
}

fun main(args: Array<String>) {
    val parser = ArgParser("Lincheck benchmarks report plotter")
    val plotType by parser.option(
        ArgType.Choice<PlotType>(),
        description = "type of the plot to draw"
    ).default(PlotType.AllPlots)
    val benchmarkName by parser.option(
        ArgType.String,
        description = "name of the benchmark"
    )
    val strategy by parser.option(
        ArgType.Choice<LincheckStrategy>(),
        description = "strategy used in the benchmark"
    )
    val reportFilename by parser.argument(
        ArgType.String,
        description = "path to the benchmarks report file (.json)"
    )
    val outputPath by parser.argument(
        ArgType.String,
        description = "path to the output plot file, or output directory if all plots were requested"
    )
    parser.parse(args)

    val file = File(reportFilename)
    val report = file.inputStream().use { inputStream ->
        Json.decodeFromStream<BenchmarksReport>(inputStream)
    }
    val outputFilename = when (plotType) {
        PlotType.AllPlots -> null
        else -> Paths.get(outputPath).fileName.toString()
    }
    val outputDirectory = when (plotType) {
        PlotType.AllPlots -> outputPath
        else -> Paths.get(outputPath).parent.toString()
    }

    if (plotType == PlotType.RunningTimeBarPlot || plotType == PlotType.AllPlots) {
        report.runningTimeBarPlot(
            filename = outputFilename ?: PlotType.RunningTimeBarPlot.defaultOutputFilename(),
            path = outputDirectory
        )
    }

    if (plotType == PlotType.InvocationTimeByScenarioSizeBarPlot || plotType == PlotType.AllPlots) {
        val benchmarkNames = when (plotType) {
            PlotType.InvocationTimeByScenarioSizeBarPlot -> {
                require(benchmarkName != null) {
                    "Benchmark name is required for $plotType"
                }
                listOf(benchmarkName!!)
            }
            PlotType.AllPlots -> report.benchmarkNames
            else -> throw IllegalStateException()
        }
        for (name in benchmarkNames) {
            report.invocationTimeByScenarioSizeBarPlot(
                benchmarkName = name,
                filename = "${name}-${PlotType.RunningTimeBarPlot.defaultOutputFilename()}",
                path = outputDirectory,
            )
        }
    }

    if (plotType == PlotType.InvocationTimeScatterPlot || plotType == PlotType.AllPlots) {
        val benchmarkIDs = when (plotType) {
            PlotType.InvocationTimeScatterPlot -> {
                require(benchmarkName != null) {
                    "Benchmark name is required for $plotType"
                }
                require(strategy != null) {
                    "Strategy name is required for $plotType"
                }
                val benchmark = report.data.values.find {
                    it.name == benchmarkName && it.strategy == strategy
                }
                require(benchmark != null) {
                    """
                        Benchmark with the given parameters has not been found in the report:
                            benchmark name = $benchmarkName
                            strategy name = $strategy
                    """.trimIndent()
                }
                listOf(benchmark.id)
            }
            PlotType.AllPlots -> report.benchmarkIDs
            else -> throw IllegalStateException()
        }
        for (benchmarkID in benchmarkIDs) {
            val benchmark = report.data[benchmarkID]!!
            val plotFilename = PlotType.InvocationTimeScatterPlot.defaultOutputFilename()
            report.invocationTimeScatterPlot(
                benchmarkID = benchmarkID,
                filename = "${benchmark.name}-${benchmark.strategy}-${plotFilename}",
                path = outputDirectory,
            )
        }
    }
}