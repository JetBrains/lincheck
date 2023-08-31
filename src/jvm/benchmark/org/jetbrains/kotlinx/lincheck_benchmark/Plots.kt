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
}