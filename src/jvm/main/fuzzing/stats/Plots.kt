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

import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.geom.geomText
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleColorDiscrete
import org.jetbrains.letsPlot.scale.scaleXContinuous
import org.jetbrains.letsPlot.themes.theme


fun plotSingle(
    test: BenchmarkStats,
    filename: String,
    path: String? = null
) {
    val xScale = (1..test.iterations).toList()
    val statInfo = "t=${durationToString(test.totalDurationMs)}, " +
            "invocations=${test.invocationsPerIteration}, " +
            "threads=${test.threads}, " +
            "pa=${test.actorsPerThread}, " +
            "ia=${test.initActors}, " +
            "pa=${test.postActors}"

    // total
    linePlot(
        filename = "${filename + if (test.type == TestType.MODEL_CHECKING) "-model-checking" else "-fuzzing"}-total-coverage.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { addAll((1..test.totalFoundCoverage.size).toList()) },
            "y" to test.totalFoundCoverage,
            "type" to List(test.totalFoundCoverage.size) { test.type!!.toString() }
        ),
        markedDots = mapOf(
            "x" to test.failedIterations,
            "y" to test.failedIterations.map { test.totalFoundCoverage[it - 1] },
            "type" to test.failedIterations.map { "[Found error]" }
        ),
        title = "Total coverage per iteration ($statInfo)",
        xLab = "Iteration",
        yLab = "Total covered edges count",
        xScale = xScale
    )

    // max
    linePlot(
        filename = "${filename + if (test.type == TestType.MODEL_CHECKING) "-model-checking" else "-fuzzing"}-max-coverage.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { addAll((1..test.maxIterationFoundCoverage.size).toList()) },
            "y" to test.maxIterationFoundCoverage,
            "type" to List(test.maxIterationFoundCoverage.size) { test.type!!.toString() }
        ),
        markedDots = mapOf(
            "x" to test.failedIterations,
            "y" to test.failedIterations.map { test.maxIterationFoundCoverage[it - 1] },
            "type" to test.failedIterations.map { "[Found error]" }
        ),
        title = "Max coverage per iteration ($statInfo)",
        xLab = "Iteration",
        yLab = "Max covered edges count",
        xScale = xScale
    )

    // per iteration
    linePlot(
        filename = "${filename + if (test.type == TestType.MODEL_CHECKING) "-model-checking" else "-fuzzing"}-coverage.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { addAll((1..test.iterationFoundCoverage.size).toList()) },
            "y" to test.iterationFoundCoverage,
            "type" to List(test.iterationFoundCoverage.size) { test.type!!.toString() }
        ),
        markedDots = mapOf(
            "x" to test.failedIterations,
            "y" to test.failedIterations.map { test.iterationFoundCoverage[it - 1] },
            "type" to test.failedIterations.map { "[Found error]" }
        ),
        title = "Coverage per iteration ($statInfo)",
        xLab = "Iteration",
        yLab = "Covered edges count",
        xScale = xScale
    )

    // trace total
    linePlot(
        filename = "${filename + if (test.type == TestType.MODEL_CHECKING) "-model-checking" else "-fuzzing"}-total-hb-pairs.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { addAll((1..test.totalHappensBeforePairs.size).toList()) },
            "y" to test.totalHappensBeforePairs,
            "type" to List(test.totalHappensBeforePairs.size) { test.type!!.toString() }
        ),
        title = "Total hb-pairs per iteration ($statInfo)",
        xLab = "Iteration",
        yLab = "Total covered hb-pairs count",
        xScale = xScale
    )

    // trace max
    linePlot(
        filename = "${filename + if (test.type == TestType.MODEL_CHECKING) "-model-checking" else "-fuzzing"}-max-hb-pairs.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { addAll((1..test.maxIterationHappensBeforePairs.size).toList()) },
            "y" to test.maxIterationHappensBeforePairs,
            "type" to List(test.maxIterationHappensBeforePairs.size) { test.type!!.toString() }
        ),
        title = "Max hb-pairs per iteration ($statInfo)",
        xLab = "Iteration",
        yLab = "Max covered hb-pairs count",
        xScale = xScale
    )

    // trace per iteration
    linePlot(
        filename = "${filename + if (test.type == TestType.MODEL_CHECKING) "-model-checking" else "-fuzzing"}-hb-pairs.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { addAll((1..test.iterationHappensBeforePairs.size).toList()) },
            "y" to test.iterationHappensBeforePairs,
            "type" to List(test.iterationHappensBeforePairs.size) { test.type!!.toString() }
        ),
        title = "hb-pairs per iteration ($statInfo)",
        xLab = "Iteration",
        yLab = "Covered hb-pairs count",
        xScale = xScale
    )

    // distinct traces
    linePlot(
        filename = "${filename + if (test.type == TestType.MODEL_CHECKING) "-model-checking" else "-fuzzing"}-traces.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { addAll((1..test.distinctTraces.size).toList()) },
            "y" to test.distinctTraces,
            "type" to List(test.distinctTraces.size) { test.type!!.toString() }
        ),
        title = "Distinct traces ($statInfo)",
        xLab = "Iteration",
        yLab = "Distinct traces count",
        xScale = xScale
    )
}

fun plotMerged(
    tests: List<BenchmarkStats>,
    filename: String,
    path: String? = null
) {
    val xScale = (1..tests.maxOf { it.iterations }).toList()

    // total
    linePlot(
        filename = "$filename-total-coverage.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { tests.forEach { addAll((1..it.totalFoundCoverage.size).toList()) } },
            "y" to tests.map { it.totalFoundCoverage }.flatten(),
            "type" to tests.map { test -> List(test.totalFoundCoverage.size) { test.type!!.toString() } }.flatten()
        ),
        markedDots = mapOf(
            "x" to tests.map { it.failedIterations }.flatten(),
            "y" to tests.map { test -> test.failedIterations.map { test.totalFoundCoverage[it - 1] } }.flatten(),
            "type" to tests.map { test -> test.failedIterations.map { "[Found error]" } }.flatten()
        ),
        title = "Total coverage per iteration",
        xLab = "Iteration",
        yLab = "Total covered edges count",
        xScale = xScale
    )

    // max
    linePlot(
        filename = "$filename-max-coverage.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { tests.forEach { addAll((1..it.maxIterationFoundCoverage.size).toList()) } },
            "y" to tests.map { it.maxIterationFoundCoverage }.flatten(),
            "type" to tests.map { test -> List(test.maxIterationFoundCoverage.size) { test.type!!.toString() } }.flatten()
        ),
        markedDots = mapOf(
            "x" to tests.map { it.failedIterations }.flatten(),
            "y" to tests.map { test -> test.failedIterations.map { test.maxIterationFoundCoverage[it - 1] } }.flatten(),
            "type" to tests.map { test -> test.failedIterations.map { "[Found error]" } }.flatten()
        ),
        title = "Max coverage per iteration",
        xLab = "Iteration",
        yLab = "Max covered edges count",
        xScale = xScale
    )

    // per iteration
    linePlot(
        filename = "$filename-coverage.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { tests.forEach { addAll((1..it.iterationFoundCoverage.size).toList()) } },
            "y" to tests.map { it.iterationFoundCoverage }.flatten(),
            "type" to tests.map { test -> List(test.iterationFoundCoverage.size) { test.type!!.toString() } }.flatten()
        ),
        markedDots = mapOf(
            "x" to tests.map { it.failedIterations }.flatten(),
            "y" to tests.map { test -> test.failedIterations.map { test.iterationFoundCoverage[it - 1] } }.flatten(),
            "type" to tests.map { test -> test.failedIterations.map { "[Found error]" } }.flatten()
        ),
        title = "Coverage per iteration",
        xLab = "Iteration",
        yLab = "Covered edges count",
        xScale = xScale
    )

    // trace total
    linePlot(
        filename = "$filename-total-hb-pairs.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { tests.forEach { addAll((1..it.totalHappensBeforePairs.size).toList()) } },
            "y" to tests.map { it.totalHappensBeforePairs }.flatten(),
            "type" to tests.map { test -> List(test.totalHappensBeforePairs.size) { test.type!!.toString() } }.flatten()
        ),
        title = "Total hb-pairs per iteration",
        xLab = "Iteration",
        yLab = "Total hb-pairs count",
        xScale = xScale
    )

    // trace max
    linePlot(
        filename = "$filename-max-hb-pairs.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { tests.forEach { addAll((1..it.maxIterationHappensBeforePairs.size).toList()) } },
            "y" to tests.map { it.maxIterationHappensBeforePairs }.flatten(),
            "type" to tests.map { test -> List(test.maxIterationHappensBeforePairs.size) { test.type!!.toString() } }.flatten()
        ),
        title = "Max hb-pairs per iteration",
        xLab = "Iteration",
        yLab = "Max hb-pairs count",
        xScale = xScale
    )

    // trace per iteration
    linePlot(
        filename = "$filename-hb-pairs.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { tests.forEach { addAll((1..it.iterationHappensBeforePairs.size).toList()) } },
            "y" to tests.map { it.iterationHappensBeforePairs }.flatten(),
            "type" to tests.map { test -> List(test.iterationHappensBeforePairs.size) { test.type!!.toString() } }.flatten()
        ),
        title = "hb-pairs per iteration",
        xLab = "Iteration",
        yLab = "hb-pairs count",
        xScale = xScale
    )

    // distinct traces
    linePlot(
        filename = "$filename-traces.png",
        path = path,
        data = mapOf(
            "x" to mutableListOf<Int>().apply { tests.forEach { addAll((1..it.distinctTraces.size).toList()) } },
            "y" to tests.map { it.distinctTraces }.flatten(),
            "type" to tests.map { test -> List(test.distinctTraces.size) { test.type!!.toString() } }.flatten()
        ),
        title = "Distinct traces",
        xLab = "Iteration",
        yLab = "Distinct traces count",
        xScale = xScale
    )
}

private fun durationToString(durationMs: Long): String {
    return "${durationMs / 1000 / 60}m ${(durationMs / 1000) % 60}s ${durationMs % 1000}ms"
}

private fun linePlot(
    filename: String,
    path: String? = null,
    data: Map<String, List<Any>>,
    markedDots: Map<String, List<Any>> = mapOf("x" to emptyList(), "y" to emptyList(), "type" to emptyList()),
    title: String = "Line plot",
    xLab: String,
    yLab: String,
    xScale: List<Int>
) {
    //println("Plotting: data=${data}, markedDots=${markedDots}")

    val labelEachDotRatio = 10
    val plot = letsPlot() +
        ggsize(1800, 1200) +
        labs(title = title, x = xLab, y = yLab) +
        scaleXContinuous(
            breaks = xScale,
            labels = xScale.toList().map { it.toString() }
        ) +
        theme(
            plotTitle = mapOf("size" to 11.0),
            legendText = mapOf("size" to 8.0),
            axisTextX = mapOf("size" to 8.0),
            axisTextY = mapOf("size" to 8.0),
        ).legendPositionRight() +
        scaleColorDiscrete() +
        geomLine(data = data, size = 1.0) { x = "x"; y = "y"; color = "type"; group = "type" } +
        geomPoint(data = data, size = 3.0) { x = "x"; y = "y"; color = "type"; group = "type" } +
        geomPoint(data = markedDots, size = 4.0) { x = "x"; y = "y"; color = "type"; group = "type" } +
        geomText(data = mapOf(
            "x" to data["x"]!!.filterIndexed { index, _ -> (index + 1) % labelEachDotRatio == 0  },
            "y" to data["y"]!!.filterIndexed { index, _ -> (index + 1) % labelEachDotRatio == 0  },
            "type" to data["type"]!!.filterIndexed { index, _ -> (index + 1) % labelEachDotRatio == 0  }
        ), labelFormat = "d", size = 5, hjust = 1, vjust = 0) { x = "x"; y = "y"; label = "y" } +
        geomText(data = markedDots) { color = "type"; group = "type" }

    ggsave(plot, filename, path = path)
}
