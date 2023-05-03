/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.LoggingLevel.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import java.io.*

class Reporter constructor(private val logLevel: LoggingLevel) {
    private val out: PrintStream = System.out
    private val outErr: PrintStream = System.err

    fun logIteration(iteration: Int, maxIterations: Int, scenario: ExecutionScenario) = log(INFO) {
        appendln("\n= Iteration $iteration / $maxIterations =")
        appendExecutionScenario(scenario)
    }

    fun logFailedIteration(failure: LincheckFailure) = log(INFO) {
        appendFailure(failure)
    }

    fun logScenarioMinimization(scenario: ExecutionScenario) = log(INFO) {
        appendln("\nInvalid interleaving found, trying to minimize the scenario below:")
        appendExecutionScenario(scenario)
    }


    private inline fun log(logLevel: LoggingLevel, crossinline msg: StringBuilder.() -> Unit): Unit = synchronized(this) {
        if (this.logLevel > logLevel) return
        val sb = StringBuilder()
        msg(sb)
        val output = if (logLevel == WARN) outErr else out
        output.println(sb)
    }
}

@JvmField val DEFAULT_LOG_LEVEL = WARN
enum class LoggingLevel {
    INFO, WARN
}

internal fun <T> printInColumnsCustom(
        groupedObjects: List<List<T>>,
        joinColumns: (List<String>) -> String
): String {
    val nRows = groupedObjects.map { it.size }.maxOrNull() ?: 0
    val nColumns = groupedObjects.size
    val rows = (0 until nRows).map { rowIndex ->
        (0 until nColumns)
                .map { groupedObjects[it] }
                .map { it.getOrNull(rowIndex)?.toString().orEmpty() } // print empty strings for empty cells
    }
    val columnWidths: List<Int> = (0 until nColumns).map { columnIndex ->
        (0 until nRows).map { rowIndex -> rows[rowIndex][columnIndex].length }.maxOrNull() ?: 0
    }
    return (0 until nRows)
            .map { rowIndex -> rows[rowIndex].mapIndexed { columnIndex, cell -> cell.padEnd(columnWidths[columnIndex]) } }
            .map { rowCells -> joinColumns(rowCells) }
            .joinToString(separator = "\n")
}

private fun <T> printInColumns(groupedObjects: List<List<T>>) = printInColumnsCustom(groupedObjects) { it.joinToString(separator = " | ", prefix = "| ", postfix = " |") }

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
    val maxActorLength = actorRepresentations.map { it.length }.maxOrNull()!!
    val maxResultLength = resultRepresentations.map { it.length }.maxOrNull()!!
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

internal fun StringBuilder.appendExecutionScenario(scenario: ExecutionScenario): StringBuilder {
    if (scenario.initExecution.isNotEmpty()) {
        appendln("Execution scenario (init part):")
        appendln(scenario.initExecution)
    }
    if (scenario.parallelExecution.isNotEmpty()) {
        appendln("Execution scenario (parallel part):")
        append(printInColumns(scenario.parallelExecution))
        appendln()
    }
    if (scenario.postExecution.isNotEmpty()) {
        appendln("Execution scenario (post part):")
        append(scenario.postExecution)
    }
    return this
}

internal fun StringBuilder.appendFailure(failure: LincheckFailure): StringBuilder {
    when (failure) {
        is IncorrectResultsFailure -> appendIncorrectResultsFailure(failure)
        is DeadlockWithDumpFailure -> appendDeadlockWithDumpFailure(failure)
        is UnexpectedExceptionFailure -> appendUnexpectedExceptionFailure(failure)
        is ValidationFailure -> appendValidationFailure(failure)
        is ObstructionFreedomViolationFailure -> appendObstructionFreedomViolationFailure(failure)
    }
    val results = if (failure is IncorrectResultsFailure) failure.results else null
    if (failure.trace != null) {
        appendLine()
        appendLine("= The following interleaving leads to the error =")
        appendTrace(failure.scenario, results, failure.trace)
        if (failure is DeadlockWithDumpFailure) {
            appendLine()
            append("All threads are in deadlock")
        }
    }
    return this
}

private fun StringBuilder.appendUnexpectedExceptionFailure(failure: UnexpectedExceptionFailure): StringBuilder {
    appendln("= The execution failed with an unexpected exception =")
    appendExecutionScenario(failure.scenario)
    appendln()
    appendException(failure.exception)
    return this
}

private fun StringBuilder.appendDeadlockWithDumpFailure(failure: DeadlockWithDumpFailure): StringBuilder {
    appendLine("= The execution has hung, see the thread dump =")
    appendExecutionScenario(failure.scenario)
    appendLine()
    for ((t, stackTrace) in failure.threadDump) {
        val threadNumber = if (t is FixedActiveThreadsExecutor.TestThread) t.iThread.toString() else "?"
        appendLine("Thread-$threadNumber:")
        stackTrace.map {
            StackTraceElement(it.className.removePrefix(TransformationClassLoader.REMAPPED_PACKAGE_CANONICAL_NAME), it.methodName, it.fileName, it.lineNumber)
        }.map { it.toString() }.filter { line ->
            "org.jetbrains.kotlinx.lincheck.strategy" !in line
                && "org.jetbrains.kotlinx.lincheck.runner" !in line
                && "org.jetbrains.kotlinx.lincheck.UtilsKt" !in line
        }.forEach { appendLine("\t$it") }
    }
    return this
}

private fun StringBuilder.appendIncorrectResultsFailure(failure: IncorrectResultsFailure): StringBuilder {
    appendln("= Invalid execution results =")
    if (failure.scenario.initExecution.isNotEmpty()) {
        appendln("Init part:")
        appendln(uniteActorsAndResultsLinear(failure.scenario.initExecution, failure.results.initResults))
    }
    if (failure.results.afterInitStateRepresentation != null)
        appendln("STATE: ${failure.results.afterInitStateRepresentation}")
    appendln("Parallel part:")
    val parallelExecutionData = uniteParallelActorsAndResults(failure.scenario.parallelExecution, failure.results.parallelResultsWithClock)
    appendln(printInColumns(parallelExecutionData))
    if (failure.results.afterParallelStateRepresentation != null) {
        appendln("STATE: ${failure.results.afterParallelStateRepresentation}")
    }
    if (failure.scenario.postExecution.isNotEmpty()) {
        appendln("Post part:")
        appendln(uniteActorsAndResultsLinear(failure.scenario.postExecution, failure.results.postResults))
    }
    if (failure.results.afterPostStateRepresentation != null && failure.scenario.postExecution.isNotEmpty()) {
        appendln("STATE: ${failure.results.afterPostStateRepresentation}")
    }
    if (failure.results.parallelResultsWithClock.flatten().any { !it.clockOnStart.empty })
        appendln("\n---\nvalues in \"[..]\" brackets indicate the number of completed operations \n" +
            "in each of the parallel threads seen at the beginning of the current operation\n---")
    return this
}

private fun StringBuilder.appendValidationFailure(failure: ValidationFailure): StringBuilder {
    appendln("= Validation function ${failure.functionName} has failed =")
    appendExecutionScenario(failure.scenario)
    appendException(failure.exception)
    return this
}

private fun StringBuilder.appendObstructionFreedomViolationFailure(failure: ObstructionFreedomViolationFailure): StringBuilder {
    appendln("= ${failure.reason} =")
    appendExecutionScenario(failure.scenario)
    return this
}

private fun StringBuilder.appendException(t: Throwable) {
    val sw = StringWriter()
    t.printStackTrace(PrintWriter(sw))
    appendln(sw.toString())
}