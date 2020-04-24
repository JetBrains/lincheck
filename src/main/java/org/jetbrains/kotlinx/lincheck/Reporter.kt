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

import org.jetbrains.kotlinx.lincheck.LoggingLevel.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import java.io.*

class Reporter @JvmOverloads constructor(val logLevel: LoggingLevel, val out: PrintStream = System.out) {
    fun logIteration(iteration: Int, maxIterations: Int, scenario: ExecutionScenario) = log(INFO) {
        StringBuilder("\n= Iteration $iteration / $maxIterations =\n").run {
            appendExecutionScenario(scenario)
            out.println(this)
        }
    }

    fun logFailedIteration(failure: LincheckFailure) = log(INFO) {
        StringBuilder().appendFailure(failure)
    }

    fun logScenarioMinimization(scenario: ExecutionScenario) = log(INFO) {
        StringBuilder("\nInvalid interleaving found, trying to minimize the scenario below:\n").run {
            appendExecutionScenario(scenario)
            out.println(this)
        }
    }

    private inline fun log(logLevel: LoggingLevel, crossinline msg: () -> Any): Unit = synchronized(this) {
        if (this.logLevel > logLevel) return
        out.println(msg())
    }
}

@JvmField val DEFAULT_LOG_LEVEL = ERROR
enum class LoggingLevel {
    INFO, ERROR
}

private fun <T> printInColumns(groupedObjects: List<List<T>>): String {
    val nRows = groupedObjects.map { it.size }.max()!!
    val nColumns = groupedObjects.size
    val rows = (0 until nRows).map { rowIndex ->
        (0 until nColumns)
                .map { groupedObjects[it] }
                .map { it.getOrNull(rowIndex)?.toString().orEmpty() } // print empty strings for empty cells
    }
    val columnWidths: List<Int> = (0 until nColumns).map { columnIndex ->
        (0 until nRows).map { rowIndex -> rows[rowIndex][columnIndex].length }.max()!!
    }
    return (0 until nRows)
            .map { rowIndex -> rows[rowIndex].mapIndexed { columnIndex, cell -> cell.padEnd(columnWidths[columnIndex]) } }
            .map { rowCells -> rowCells.joinToString(separator = " | ", prefix = "| ", postfix = " |") }
            .joinToString(separator = "\n")
}

private class ActorWithResult(val actorRepresentation: String, val spacesAfterActor: Int,
                              val resultRepresentation: String, val spacesAfterResult: Int,
                              val clockRepresentation: String) {
    override fun toString(): String =
        actorRepresentation + ":" + " ".repeat(spacesAfterActor) + resultRepresentation +
                                    " ".repeat(spacesAfterResult) + clockRepresentation
}

private fun uniteActorsAndResultsLinear(actors: List<Actor>, results: List<Result>): List<ActorWithResult> {
    require(actors.size == results.size) {
        "Different numbers of actors and matching results found (${actors.size} != ${results.size})"
    }
    return actors.indices.map {
        ActorWithResult("${actors[it]}", 1, "${results[it]}", 0, "")
    }
}

private fun uniteParallelActorsAndResults(actors: List<List<Actor>>, results: List<List<ResultWithClock>>): List<List<ActorWithResult>> {
    require(actors.size == results.size) {
        "Different numbers of threads and matching results found (${actors.size} != ${results.size})"
    }
    return actors.mapIndexed { id, threadActors -> uniteActorsAndResultsAligned(threadActors, results[id]) }
}

private fun uniteActorsAndResultsAligned(actors: List<Actor>, results: List<ResultWithClock>): List<ActorWithResult> {
    require(actors.size == results.size) {
        "Different numbers of actors and matching results found (${actors.size} != ${results.size})"
    }
    val actorRepresentations = actors.map { it.toString() }
    val resultRepresentations = results.map { it.result.toString() }
    val maxActorLength = actorRepresentations.map { it.length }.max()!!
    val maxResultLength = resultRepresentations.map { it.length }.max()!!
    return actors.indices.map { i ->
        val actorRepr = actorRepresentations[i]
        val resultRepr = resultRepresentations[i]
        val clock = results[i].clockOnStart
        val spacesAfterActor = maxActorLength - actorRepr.length + 1
        val spacesAfterResultToAlign = maxResultLength - resultRepr.length
        if (clock.empty) {
            ActorWithResult(actorRepr, spacesAfterActor, resultRepr, spacesAfterResultToAlign, "")
        } else {
            ActorWithResult(actorRepr, spacesAfterActor, resultRepr, spacesAfterResultToAlign + 1, clock.toString())
        }
    }
}

internal fun StringBuilder.appendExecutionScenario(scenario: ExecutionScenario) {
    if (scenario.initExecution.isNotEmpty()) {
        appendln("Execution scenario (init part):")
        appendln(scenario.initExecution)
    }
    if (scenario.parallelExecution.isNotEmpty()) {
        appendln("Execution scenario (parallel part):")
        append(printInColumns(scenario.parallelExecution))
    }
    if (scenario.parallelExecution.isNotEmpty()) {
        appendln()
        appendln("Execution scenario (post part):")
        append(scenario.postExecution)
    }
}

internal fun StringBuilder.appendFailure(failure: LincheckFailure): StringBuilder =
    when (failure) {
        is IncorrectResultsFailure -> appendIncorrectResultsFailure(failure)
        is DeadlockWithDumpFailure -> appendDeadlockWithDumpFailure(failure)
        is UnexpectedExceptionFailure -> appendUnexpectedExceptionFailure(failure)
        is ValidationFailure -> appendValidationFailure(failure)
    }

private fun StringBuilder.appendUnexpectedExceptionFailure(failure: UnexpectedExceptionFailure): StringBuilder {
    appendln("= The execution failed with an unexpected exception =")
    appendExecutionScenario(failure.scenario)
    appendException(failure.exception)
    return this
}

private fun StringBuilder.appendDeadlockWithDumpFailure(failure: DeadlockWithDumpFailure): StringBuilder {
    appendln("= The execution has hung, see the thread dump =")
    appendExecutionScenario(failure.scenario)
    for ((t, stackTrace) in failure.threadDump) {
        val threadNumber = if (t is ParallelThreadsRunner.TestThread) t.iThread else "?"
        appendln("Thread-$threadNumber:")
        for (ste in stackTrace) {
            if (ste.className.startsWith("org.jetbrains.kotlinx.lincheck.runner.")) break
            appendln("\t$ste")
        }
    }
    return this
}

private fun StringBuilder.appendIncorrectResultsFailure(failure: IncorrectResultsFailure): StringBuilder {
    appendln("= Invalid execution results =")
    if (failure.scenario.initExecution.isNotEmpty()) {
        appendln("Init part:")
        appendln(uniteActorsAndResultsLinear(failure.scenario.initExecution, failure.results.initResults))
    }
    appendln("Parallel part:")
    val parallelExecutionData = uniteParallelActorsAndResults(failure.scenario.parallelExecution, failure.results.parallelResultsWithClock)
    append(printInColumns(parallelExecutionData))
    if (failure.scenario.postExecution.isNotEmpty()) {
        appendln()
        appendln("Post part:")
        append(uniteActorsAndResultsLinear(failure.scenario.postExecution, failure.results.postResults))
    }
    if (failure.results.parallelResultsWithClock.flatten().any { !it.clockOnStart.empty })
        appendln("\n---\nvalues in \"[..]\" brackets indicate the number of completed operations \n" +
            "in each of the parallel threads seen at the beginning of the current operation\n---")
    return this
}

private fun StringBuilder.appendValidationFailure(failure: ValidationFailure): StringBuilder {
    appendln("= Validation function ${failure.functionName} has been failed =")
    appendExecutionScenario(failure.scenario)
    appendException(failure.exception)
    return this
}

private fun StringBuilder.appendException(t: Throwable) {
    val sw = StringWriter()
    t.printStackTrace(PrintWriter(sw))
    appendln(sw.toString())
}