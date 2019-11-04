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
import java.io.PrintWriter
import java.io.StringWriter

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

fun StringBuilder.appendlnStackTrace(e: Throwable) {
    val sw = StringWriter()
    val pw = PrintWriter(sw)
    val reducedStackTrace = mutableListOf<StackTraceElement>()
    for (ste in e.stackTrace) {
        if (ste.className.startsWith("org.jetbrains.kotlinx.lincheck.runner.")) break
        reducedStackTrace.add(ste)
    }
    e.stackTrace = reducedStackTrace.toTypedArray()
    e.printStackTrace(pw)
    appendln(sw.toString())
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
        results: ExecutionResult?,
        interleavingEvents: List<InterleavingEvent>
) {
    val nThreads = scenario.threads
    // last actor that was handled for each thread
    val lastStartedActor = IntArray(nThreads) { -1 }
    // what actors should be printed with all their inner events
    val shouldBeDetailedActors = Array(nThreads) { mutableSetOf<Int>() }
    // what actors started execution at last or the number of actors in each thread, if every actor successfully finished the execution
    val lastExecutedActors = Array(nThreads) { threadId -> interleavingEvents.filter { it.threadId == threadId}.map { it.actorId }.max() }

    // study what actors should be printed in detail
    for (event in interleavingEvents)
        if (event is SwitchEvent || event is SuspendSwitchEvent || event.actorId == lastExecutedActors[event.threadId])
            shouldBeDetailedActors[event.threadId].add(event.actorId)

    // an event that is represented by the number of a thread it refers to and by what strings should be shown in two columns
    class InterleavingRepresentation(val threadId: Int, val left: String, val right: String)

    // convert events that should be printed to the final form of a matrix of strings
    fun splitToColumns(nThreads: Int, execution: List<InterleavingRepresentation>): List<List<String>> {
        val result = List(nThreads * 2) { mutableListOf<String>() }
        for (message in execution) {
            val firstColumn = 2 * message.threadId
            val secondColumn = 2 * message.threadId + 1
            // write messages in appropriate columns
            result[firstColumn].add(message.left)
            result[secondColumn].add(message.right)
            val neededSize = result[firstColumn].size
            for (column in result)
                if (column.size != neededSize)
                    column.add("")
        }
        return result
    }

    fun getParallelResult(threadId: Int, actorId: Int) = if (results == null) "*" else "* result: ${results.parallelResults[threadId][actorId]}"

    val execution = mutableListOf<InterleavingRepresentation>()
    for (event in interleavingEvents) {
        val threadId = event.threadId
        val actorId = event.actorId

        if (lastStartedActor[threadId] < actorId) {
            // a new actor has started
            while (lastStartedActor[threadId] < actorId) {
                // print actors while they are older than the current
                val lastActor = lastStartedActor[threadId]
                if (lastActor != -1 && lastActor in shouldBeDetailedActors[threadId])
                    execution.add(InterleavingRepresentation(threadId, "", getParallelResult(threadId, lastActor)))
                val nextActor = ++lastStartedActor[threadId]
                if (nextActor != scenario.parallelExecution[threadId].size) {
                    // print actor
                    // if is should not be printed in details then print with the result in the same line
                    if (nextActor !in shouldBeDetailedActors[threadId])
                        execution.add(InterleavingRepresentation(
                                threadId,
                                "${scenario.parallelExecution[threadId][nextActor]}",
                                getParallelResult(threadId, nextActor)
                        ))
                    else
                        execution.add(InterleavingRepresentation(threadId, "${scenario.parallelExecution[threadId][nextActor]}", "*"))
                }
            }
        }
        when (event) {
            is SwitchEvent -> {
                execution.add(InterleavingRepresentation(threadId, "", "switch at: ${shorten(event.info.toString())}"))
                // print reason if any
                if (event.reason.toString().isNotEmpty())
                    execution.add(InterleavingRepresentation(threadId, "", "reason: ${event.reason}"))
            }
            is SuspendSwitchEvent -> {
                execution.add(InterleavingRepresentation(threadId, "", "switch"))
                execution.add(InterleavingRepresentation(threadId, "", "reason: ${event.reason}"))
            }
            is FinishEvent -> {
                execution.add(InterleavingRepresentation(threadId, "", "thread is finished"))
            }
            is PassCodeLocationEvent -> {
                if (actorId in shouldBeDetailedActors[threadId])
                    execution.add(InterleavingRepresentation(threadId, "", "pass: ${shorten(event.codeLocation.toString())}"))
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