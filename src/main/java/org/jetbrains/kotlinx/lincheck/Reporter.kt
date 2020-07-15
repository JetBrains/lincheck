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
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.FinishEvent
import org.jetbrains.kotlinx.lincheck.strategy.managed.PassCodeLocationEvent
import org.jetbrains.kotlinx.lincheck.strategy.managed.SuspendSwitchEvent
import org.jetbrains.kotlinx.lincheck.strategy.managed.SwitchEvent
import java.io.*

class Reporter @JvmOverloads constructor(val logLevel: LoggingLevel, val out: PrintStream = System.out) {
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
        out.println(sb)
    }
}

@JvmField val DEFAULT_LOG_LEVEL = ERROR
enum class LoggingLevel {
    INFO, ERROR
}

private fun <T> printInColumnsCustom(
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
        appendln(scenario.postExecution)
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
    if (failure.execution != null) {
        appendln()
        appendln("= The execution that led to this result =")
        appendExecution(failure.scenario, results, failure.execution)
        if (failure is DeadlockWithDumpFailure)
            appendln("All threads are in deadlock")
    }
    return this
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
        val threadNumber = if (t is ParallelThreadsRunner.TestThread) t.threadId else "?"
        appendln("Thread-$threadNumber:")
        for (ste in stackTrace) {
            if (ste.className.startsWith("org.jetbrains.kotlinx.lincheck.runner.")) break
            // omit information about strategy code insertions
            if (ste.className.startsWith("org.jetbrains.kotlinx.lincheck.strategy.")) continue
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

private fun StringBuilder.appendObstructionFreedomViolationFailure(failure: ObstructionFreedomViolationFailure): StringBuilder {
    appendln("= Obstruction freedom check was required, but has failed. Reason: ${failure.reason} =")
    appendExecutionScenario(failure.scenario)
    return this
}

private fun StringBuilder.appendException(t: Throwable) {
    val sw = StringWriter()
    t.printStackTrace(PrintWriter(sw))
    appendln(sw.toString())
}

private fun StringBuilder.appendExecution(
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
                execution.add(InterleavingRepresentation(threadId, "", "switch at: ${event.info.shorten()}"))
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
                    execution.add(InterleavingRepresentation(threadId, "", "pass: ${event.codeLocation.shorten()}"))
            }
        }
    }

    val executionData = splitToColumns(nThreads, execution)

    appendln("= Parallel part execution: =")
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
private fun StackTraceElement.shorten(): String {
    val stackTraceElement = this.toString().replace('/', '.')
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