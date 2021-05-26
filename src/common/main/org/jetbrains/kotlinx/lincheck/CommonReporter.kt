/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.LoggingLevel.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import kotlinx.atomicfu.locks.*
import kotlin.jvm.*

class Reporter @JvmOverloads constructor(private val logLevel: LoggingLevel) : SynchronizedObject() {
    fun logIteration(iteration: Int, maxIterations: Int, scenario: ExecutionScenario) = log(INFO) {
        appendLine("\n= Iteration $iteration / $maxIterations =")
        appendExecutionScenario(scenario)
    }

    fun logFailedIteration(failure: LincheckFailure) = log(INFO) {
        appendFailure(failure)
    }

    fun logScenarioMinimization(scenario: ExecutionScenario) = log(INFO) {
        appendLine("\nInvalid interleaving found, trying to minimize the scenario below:")
        appendExecutionScenario(scenario)
    }

    private inline fun log(logLevel: LoggingLevel, crossinline msg: StringBuilder.() -> Unit) = synchronized(this) {
        if (this.logLevel > logLevel) return
        val sb = StringBuilder()
        msg(sb)
        println(sb)
    }
}

@JvmField val DEFAULT_LOG_LEVEL = ERROR
enum class LoggingLevel {
    INFO, ERROR
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

internal expect fun StringBuilder.appendFailure(failure: LincheckFailure): StringBuilder

internal fun StringBuilder.appendUnexpectedExceptionFailure(failure: UnexpectedExceptionFailure): StringBuilder {
    appendLine("= The execution failed with an unexpected exception =")
    appendExecutionScenario(failure.scenario)
    appendLine()
    appendException(failure.exception)
    return this
}

internal expect fun StringBuilder.appendDeadlockWithDumpFailure(failure: DeadlockWithDumpFailure): StringBuilder

internal fun StringBuilder.appendIncorrectResultsFailure(failure: IncorrectResultsFailure): StringBuilder {
    appendLine("= Invalid execution results =")
    if (failure.scenario.initExecution.isNotEmpty()) {
        appendLine("Init part:")
        appendLine(uniteActorsAndResultsLinear(failure.scenario.initExecution, failure.results.initResults))
    }
    if (failure.results.afterInitStateRepresentation != null)
        appendLine("STATE: ${failure.results.afterInitStateRepresentation}")
    appendLine("Parallel part:")
    val parallelExecutionData = uniteParallelActorsAndResults(failure.scenario.parallelExecution, failure.results.parallelResultsWithClock)
    append(printInColumns(parallelExecutionData))
    if (failure.results.afterParallelStateRepresentation != null) {
        appendLine()
        append("STATE: ${failure.results.afterParallelStateRepresentation}")
    }
    if (failure.scenario.postExecution.isNotEmpty()) {
        appendLine()
        appendLine("Post part:")
        append(uniteActorsAndResultsLinear(failure.scenario.postExecution, failure.results.postResults))
    }
    if (failure.results.afterPostStateRepresentation != null && failure.scenario.postExecution.isNotEmpty()) {
        appendLine()
        append("STATE: ${failure.results.afterPostStateRepresentation}")
    }
    if (failure.results.parallelResultsWithClock.flatten().any { !it.clockOnStart.empty })
        appendLine("\n---\nvalues in \"[..]\" brackets indicate the number of completed operations \n" +
            "in each of the parallel threads seen at the beginning of the current operation\n---")
    return this
}

internal fun StringBuilder.appendValidationFailure(failure: ValidationFailure): StringBuilder {
    appendLine("= Validation function ${failure.functionName} has failed =")
    appendExecutionScenario(failure.scenario)
    appendException(failure.exception)
    return this
}

internal fun StringBuilder.appendObstructionFreedomViolationFailure(failure: ObstructionFreedomViolationFailure): StringBuilder {
    appendLine("= ${failure.reason} =")
    appendExecutionScenario(failure.scenario)
    return this
}

private fun StringBuilder.appendException(t: Throwable) {
    appendLine(t.stackTraceToString())
}