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
import org.jetbrains.kotlinx.lincheck.strategy.*
import java.io.PrintStream

class Reporter @JvmOverloads constructor(val logLevel: LoggingLevel, val out: PrintStream = System.out) {
    fun logIteration(iteration: Int, maxIterations: Int, scenario: ExecutionScenario) = synchronized(this) {
        if (logLevel > LoggingLevel.INFO) return
        StringBuilder("\n= Iteration $iteration / $maxIterations =\n").run {
            appendExecutionScenario(scenario)
            out.println(this)
        }
    }

    fun logScenarioMinimization(scenario: ExecutionScenario) {
        if (logLevel > LoggingLevel.INFO) return
        StringBuilder("\nInvalid interleaving found, trying to minimize the scenario below:\n").run {
            appendExecutionScenario(scenario)
            out.println(this)
        }
    }
}

@JvmField val DEFAULT_LOG_LEVEL = LoggingLevel.ERROR
enum class LoggingLevel {
    DEBUG, INFO, ERROR
}

private inline fun <T> printInColumnsCustom(
        groupedObjects: List<List<T>>,
        joinColumns: (List<String>) -> String
): String {
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
            .map { rowCells -> joinColumns(rowCells) }
            .joinToString(separator = "\n")
}

private fun <T> printInColumns(groupedObjects: List<List<T>>) = printInColumnsCustom(groupedObjects) { it.joinToString(separator = " | ", prefix = "| ", postfix = " |") }


private class ActorWithResult(val actorRepresentation: String, val spaces: Int, val resultRepresentation: String) {
    override fun toString(): String = actorRepresentation + ":" + " ".repeat(spaces) + resultRepresentation
}

private fun uniteActorsAndResults(actors: List<Actor>, results: List<Result>): List<ActorWithResult> {
    require(actors.size == results.size) {
        "Different numbers of actors and matching results found (${actors.size} != ${results.size})"
    }
    return actors.indices.map { ActorWithResult("${actors[it]}", 1, "${results[it]}") }
}

private fun uniteParallelActorsAndResults(actors: List<List<Actor>>, results: List<List<Result>>): List<List<ActorWithResult>> {
    require(actors.size == results.size) {
        "Different numbers of threads and matching results found (${actors.size} != ${results.size})"
    }
    return actors.mapIndexed { id, threadActors -> uniteActorsAndResultsAligned(threadActors, results[id]) }
}

private fun uniteActorsAndResultsAligned(actors: List<Actor>, results: List<Result>): List<ActorWithResult> {
    require(actors.size == results.size) {
        "Different numbers of actors and matching results found (${actors.size} != ${results.size})"
    }
    val actorRepresentations = actors.map { it.toString() }
    val maxActorLength = actorRepresentations.map { it.length }.max()!!
    return actorRepresentations.mapIndexed { id, actorRepr ->
        val spaces = 1 + maxActorLength - actorRepr.length
        ActorWithResult(actorRepr, spaces, "${results[id]}")
    }
}

fun StringBuilder.appendExecutionScenario(scenario: ExecutionScenario) {
    if (scenario.initExecution.isNotEmpty()) {
        appendln("Execution scenario (init part):")
        appendln(scenario.initExecution)
    }
    appendln("Execution scenario (parallel part):")
    append(printInColumns(scenario.parallelExecution))
    if (scenario.parallelExecution.isNotEmpty()) {
        appendln()
        appendln("Execution scenario (post part):")
        append(scenario.postExecution)
    }
}

fun StringBuilder.appendIncorrectResults(scenario: ExecutionScenario, results: ExecutionResult) {
    appendln("= Invalid execution results: =")
    if (scenario.initExecution.isNotEmpty()) {
        appendln("Init part:")
        appendln(uniteActorsAndResults(scenario.initExecution, results.initResults))
    }
    appendln("Parallel part:")
    val parallelExecutionData = uniteParallelActorsAndResults(scenario.parallelExecution, results.parallelResults)
    append(printInColumns(parallelExecutionData))
    if (scenario.postExecution.isNotEmpty()) {
        appendln()
        appendln("Post part:")
        append(uniteActorsAndResults(scenario.postExecution, results.postResults))
    }
}

fun StringBuilder.appendIncorrectInterleaving(
        scenario: ExecutionScenario,
        results: ExecutionResult,
        interleavingEvents: List<InterleavingEvent>
) {
    val nThreads = scenario.threads
    val lastStartedActor = IntArray(nThreads) { -1 }
    val interestingActors = Array(nThreads) { mutableSetOf<Int>() }

    for (event in interleavingEvents) {
        if (event is SwitchEvent || event is SuspendSwitchEvent) {
            interestingActors[event.iThread].add(event.iActor)
        }
    }

    class InterleavingRepresentation(val iThread: Int, val left: String, val right: String)

    fun splitToColumns(nThreads: Int, execution: List<InterleavingRepresentation>): List<List<String>> {
        val result = List(nThreads * 2) { mutableListOf<String>() }
        for (message in execution) {
            val firstColumn = 2 * message.iThread
            val secondColumn = 2 * message.iThread + 1

            result[firstColumn].add(message.left)
            result[secondColumn].add(message.right)

            val neededSize = result[firstColumn].size

            for (column in result)
                if (column.size != neededSize)
                    column.add("")
        }

        return result
    }

    val execution = mutableListOf<InterleavingRepresentation>()

    for (event in interleavingEvents) {
        val iThread = event.iThread
        val iActor = event.iActor

        if (lastStartedActor[iThread] < iActor) {
            while (lastStartedActor[iThread] < iActor) {
                val lastActor = lastStartedActor[iThread]

                if (lastActor != -1 && lastActor in interestingActors[iThread])
                    execution.add(InterleavingRepresentation(iThread, "", "* result: ${results.parallelResults[iThread][lastActor]}"))

                val nextActor = ++lastStartedActor[iThread]

                if (nextActor != scenario.parallelExecution[iThread].size) {
                    // print actor
                    // if is not interesting then print with the result in the same line
                    if (nextActor !in interestingActors[iThread])
                        execution.add(InterleavingRepresentation(
                                iThread,
                                "${scenario.parallelExecution[iThread][nextActor]}",
                                "* result: ${results.parallelResults[iThread][nextActor]}"
                        ))
                    else
                        execution.add(InterleavingRepresentation(iThread, "${scenario.parallelExecution[iThread][nextActor]}", "*"))
                }
            }
        }
        when (event) {
            is SwitchEvent -> {
                execution.add(InterleavingRepresentation(iThread, "", "switch at: ${shorten(event.info.toString())}"))
                // print reason if any
                if (event.reason.toString().isNotEmpty())
                    execution.add(InterleavingRepresentation(iThread, "", "reason: ${event.reason}"))
            }
            is SuspendSwitchEvent -> {
                execution.add(InterleavingRepresentation(iThread, "", "switch"))
                execution.add(InterleavingRepresentation(iThread, "", "reason: ${event.reason}"))
            }
            is FinishEvent -> {
                execution.add(InterleavingRepresentation(iThread, "", "thread is finished"))
            }
            is PassCodeLocationEvent -> {
                if (iActor in interestingActors[iThread])
                    execution.add(InterleavingRepresentation(iThread, "", "pass: ${shorten(event.codeLocation.toString())}"))
            }
        }
    }

    val executionData = splitToColumns(nThreads, execution)

    appendln("Parallel part execution:")
    appendln(printInColumnsCustom(executionData) {
        val builder = StringBuilder()
        for (i in it.indices) {
            if (i % 2 == 0)
                builder.append(if (i == 0) "| " else " | ")
            else
                builder.append(' ')
            builder.append(it[i])
        }
        builder.append(" |")

        builder.toString()
    })
}

/**
 * Removes info about package in a stack trace element representation
 */
private fun shorten(stackTraceElementRepr: String): String {
    val stackTraceElement = stackTraceElementRepr.replace('/', '.')
    var wasPoints = 0
    for ((i, c) in stackTraceElement.withIndex().reversed()) {
        if (c == '.') {
            wasPoints++
            if (wasPoints == 3)
                return stackTraceElement.drop(i + 1)
        }
    }

    return stackTraceElement
}