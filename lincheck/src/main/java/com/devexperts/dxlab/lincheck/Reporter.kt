/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

package com.devexperts.dxlab.lincheck

import com.devexperts.dxlab.lincheck.execution.ExecutionResult
import com.devexperts.dxlab.lincheck.execution.ExecutionScenario
import java.io.PrintStream


class Reporter @JvmOverloads constructor(val logLevel: LoggingLevel, val out: PrintStream = System.out) {
    fun logIteration(iteration: Int, maxIterations: Int, scenario: ExecutionScenario) = synchronized(this) {
        if (logLevel > LoggingLevel.INFO) return
        out.println()
        out.println("= Iteration $iteration / $maxIterations =")
        logExecutionScenario(scenario)
    }

    private fun logExecutionScenario(scenario: ExecutionScenario) {
        out.println("Execution scenario (init part):")
        out.println(scenario.initExecution)
        out.println("Execution scenario (parallel part):")
        out.println(printInColumns(scenario.parallelExecution))
        out.println("Execution scenario (post part):")
        out.println(scenario.postExecution)
    }

    fun logIncorrectResults(scenario: ExecutionScenario, results: ExecutionResult) = synchronized(this) {
        out.println("= Invalid execution results: =")
        if (logLevel > LoggingLevel.INFO) { // scenario was not logged before
            logExecutionScenario(scenario)
            out.println()
        }
        out.println("Execution results (init part):")
        out.println(results.initResults)
        out.println("Execution results (parallel part):")
        out.println(printInColumns(results.parallelResults))
        out.println("Execution results (post part):")
        out.println(results.postResults)
    }

    inline fun log(logLevel: LoggingLevel, crossinline msg: () -> String) {
        if (this.logLevel > logLevel) return
        out.println(msg())
    }
}

@JvmField val DEFAULT_LOG_LEVEL = LoggingLevel.ERROR
enum class LoggingLevel {
    DEBUG, INFO, WARN, ERROR
}

private fun <T> printInColumns(groupedObjects: List<List<T>>): String {
    val nRows = groupedObjects.map { it.size }.max()!!
    val nColumns = groupedObjects.size
    val rows = (0 until nRows).map { rowIndex ->
        (0 until nColumns)
                .map { groupedObjects[it] }
                .map { it.getOrNull(rowIndex)?.toString().orEmpty() } // print empty strings for empty cells
    }
    val columndWidths: List<Int> = (0 until nColumns).map { columnIndex ->
        (0 until nRows).map { rowIndex -> rows[rowIndex][columnIndex].length }.max()!!
    }
    return (0 until nRows)
            .map { rowIndex -> rows[rowIndex].mapIndexed { columnIndex, cell -> cell.padEnd(columndWidths[columnIndex]) } }
            .map { rowCells -> rowCells.joinToString(separator = " | ", prefix = "| ", postfix = " |") }
            .joinToString(separator = "\n")
}
