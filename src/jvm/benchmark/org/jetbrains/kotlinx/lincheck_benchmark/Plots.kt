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

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.types.*
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
import kotlin.io.path.inputStream


/**
 * Generates a bar plot depicting the total running time of various benchmarks
 * under different strategies and saves it as a file.
 *
 * The plot includes benchmark names on the x-axis and
 * corresponding running times (in milliseconds) on the y-axis.
 * Different strategies employed for the benchmarks are represented by different colors in the bar plot.
 *
 * @param filename The name of the file where the plot will be saved.
 * @param path The optional directory path where the file will be saved.
 *   If null, the plot is saved in the `lets-plot-images` folder inside the current working directory.
 */
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
    ggsave(plot, filename, path = path)
}

private fun BenchmarksReport.runningTimeData(
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

/**
 * Generates a bar plot showing the average invocation times by scenario size
 * for a specified benchmark and saves it as a file.
 *
 * The plot displays the x-axis as a tuple of (number of threads, number of operations)
 * and the y-axis as the average time taken for invocations in microseconds.
 * Different strategies employed in the benchmarks are differentiated using different fill colors.
 * Optionally, error bars are included if the time error low and high values are present in the data.
 *
 * @param benchmarkName The name of the benchmark for which the plot is to be generated.
 * @param filename The name of the file where the generated plot will be saved.
 * @param path An optional directory path where the file will be saved.
 *   If null, the plot is saved in the `lets-plot-images` folder inside the current working directory.
 */
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
    if ("timeErrorLow" in data && "timeErrorHigh" in data) {
        plot += geomErrorBar(
            width = .1,
            position = positionDodge(0.9),
        ) {
            ymin = "timeErrorLow"
            ymax = "timeErrorHigh"
            group = "strategy"
        }
    }
    ggsave(plot, filename, path = path)
}

private fun BenchmarksReport.invocationTimeByScenarioSizeData(
    benchmarkName: String,
    durationUnit: DurationUnit = DurationUnit.MICROSECONDS,
): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val benchmarks = data.values
        .filter { it.name == benchmarkName }
        .map { (it.strategy to it.scenariosStatistics) }
    val isStandardErrorDefined: Boolean = benchmarks.all { (_, scenariosStatistics) ->
        scenariosStatistics.all { it.invocationStandardErrorTimeNano >= 0L }
    }
    map["params"] = benchmarks.flatMap { (_, stats) ->
        stats.map { (it.threads to it.operations).toString() }
    }
    map["strategy"] = benchmarks.flatMap { (strategy, stats) ->
        stats.map { strategy.toString() }
    }
    map["timeAverage"] = benchmarks.flatMap { (_, stats) ->
        stats.map { it.invocationAverageTimeNano.nanoseconds.toLong(durationUnit) }
    }
    if (isStandardErrorDefined) {
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
    }
    return map
}

/**
 * Generates a scatter plot depicting the invocation times for a given benchmark and saves it as a file.
 *
 * The plot includes invocation IDs on the x-axis and corresponding running times (in microseconds) on the y-axis.
 *
 * @param benchmarkID The identifier of the benchmark to generate the scatter plot for.
 * @param filename The name of the file where the plot will be saved.
 * @param path The optional directory path where the file will be saved.
 *   If null, the plot is saved in the `lets-plot-images` folder inside the current working directory.
 */
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
    ggsave(plot, filename, path = path)
}

private fun BenchmarksReport.invocationsTimeData(benchmarkID: BenchmarkID,
     durationUnit: DurationUnit = DurationUnit.MICROSECONDS,
): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val invocationsRunningTimeNano = data[benchmarkID]!!.invocationsRunningTimeNano
        .apply { convertTo(durationUnit) }
    map["invocationID"] = invocationsRunningTimeNano.indices.toList()
    map["invocationRunningTimeNano"] = invocationsRunningTimeNano
    return map
}

/**
 * Represents the type of plots that can be generated from benchmark reports.
 *
 * Available plot types.
 * - [AllPlots]: generates all available plot types.
 *
 * - [RunningTimeBarPlot]: generates a bar plot of the total running time
 *   for all benchmarks (see [runningTimeBarPlot]).
 *
 * - [InvocationTimeByScenarioSizeBarPlot]: generates a bar plot of invocation time by scenario size
 *   for a specific benchmark (see [invocationTimeByScenarioSizeBarPlot]).
 *
 * - [InvocationTimeScatterPlot]: generates a scatter plot of invocation times
 *   for a specific benchmark (see [invocationTimeScatterPlot]).
 */
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
    PlotType.InvocationTimeScatterPlot -> "invocation-time-scatter-plot.$defaultPlotExtension"
    else -> throw IllegalArgumentException()
}

// Defines the CLI for drawing the plots
class PlotCommand : CliktCommand() {

    val plotType by option()
        .help("type of the plot to draw")
        .enum<PlotType>()
        .default(PlotType.AllPlots)

    val benchmarkName by option()
        .help("name of the benchmark")

    val strategyName by option()
        .help("strategy used in the benchmark")
        .enum<LincheckStrategy>()

    val report by argument()
        .help("path to the benchmarks report file (.json)")
        .path(canBeFile = true, canBeDir = false)

    val output by argument()
        .help("path to the output directory")
        .path(canBeDir = true, canBeFile = false)
        .optional()

    override fun run() {

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val report = report.inputStream().use { inputStream ->
            Json.decodeFromStream<BenchmarksReport>(inputStream)
        }

        if (plotType == PlotType.RunningTimeBarPlot || plotType == PlotType.AllPlots) {
            val plotFilename = PlotType.RunningTimeBarPlot.defaultOutputFilename()
            report.runningTimeBarPlot(plotFilename, path = output?.toString())
            logProducedPlot(plotFilename)
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
                val plotFilename = "${name}-${PlotType.RunningTimeBarPlot.defaultOutputFilename()}"
                report.invocationTimeByScenarioSizeBarPlot(name, plotFilename, path = output?.toString())
                logProducedPlot(plotFilename)
            }
        }

        if (plotType == PlotType.InvocationTimeScatterPlot || plotType == PlotType.AllPlots) {
            val benchmarkIDs = when (plotType) {
                PlotType.InvocationTimeScatterPlot -> {
                    require(benchmarkName != null) {
                        "Benchmark name is required for $plotType"
                    }
                    require(strategyName != null) {
                        "Strategy name is required for $plotType"
                    }
                    val benchmark = report.data.values.find {
                        it.name == benchmarkName && it.strategy == strategyName
                    }
                    require(benchmark != null) {
                        """
                        Benchmark with the given parameters has not been found in the report:
                            benchmark name = $benchmarkName
                            strategy name = $strategyName
                    """.trimIndent()
                    }
                    listOf(benchmark.id)
                }
                PlotType.AllPlots -> report.benchmarkIDs
                else -> throw IllegalStateException()
            }
            for (benchmarkID in benchmarkIDs) {
                val benchmark = report.data[benchmarkID]!!
                val plotFilename = "${benchmark.name}-${benchmark.strategy}-" +
                        PlotType.InvocationTimeScatterPlot.defaultOutputFilename()
                report.invocationTimeScatterPlot(benchmarkID, plotFilename, path = output?.toString())
                logProducedPlot(plotFilename)
            }
        }
    }

    private fun logProducedPlot(plotFilename: String) {
        println("Produced plot $plotFilename")
    }
}

fun main(args: Array<String>) = PlotCommand().main(args)