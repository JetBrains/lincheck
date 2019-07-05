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

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
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
        out.println("Init part:")
        out.println(uniteActorsAndResults(scenario.initExecution, results.initResults))
        out.println("Parallel part:")
        val parallelExecutionData = uniteParallelActorsAndResults(scenario.parallelExecution, results.parallelResults)
        out.println(printInColumns(parallelExecutionData))
        out.println("Post part:")
        out.println(uniteActorsAndResults(scenario.postExecution, results.postResults))
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

private class ActorWithResult(val actorRepresentation: String, val spaces: Int, val resultRepresentation: String) {
    override fun toString(): String = actorRepresentation + ":" + " ".repeat(spaces) + resultRepresentation
}

private fun uniteActorsAndResults(actors: List<Actor>, results: List<Result>): List<ActorWithResult> {
    require(actors.size == results.size) {
        "Different numbers of actors and matching results found (${actors.size} != ${results.size})"
    }

    val actorRepresentations = actors.map { it.toString() }
    val resultRepresentations = results.map { it.toString() }

    val maxActorLength = actorRepresentations.map { it.length }.max()!!

    return actorRepresentations.mapIndexed { id, actorRepr ->
        val spaces = 1 + maxActorLength - actorRepr.length
        ActorWithResult(actorRepr, spaces, resultRepresentations[id])
    }
}

private fun uniteParallelActorsAndResults(
        actors: List<List<Actor>>,
        results: List<List<Result>>
): List<List<ActorWithResult>> {
    require(actors.size == results.size) {
        "Different numbers of threads and matching results found (${actors.size} != ${results.size})"
    }

    return actors.mapIndexed { id, threadActors -> uniteActorsAndResults(threadActors, results[id]) }
}