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

private fun uniteActorsAndResultsLinear(
    actors: List<Actor>,
    results: List<Result>,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): List<ActorWithResult> {
    require(actors.size == results.size) {
        "Different numbers of actors and matching results found (${actors.size} != ${results.size})"
    }
    return actors.indices.map {
        val result = results[it]
        ActorWithResult(
            actorRepresentation = "${actors[it]}",
            spacesAfterActor = 1,
            resultRepresentation = resultRepresentation(result, exceptionStackTraces),
            spacesAfterResult = 0,
            clockRepresentation = ""
        )
    }
}

private fun uniteParallelActorsAndResults(
    actors: List<List<Actor>>,
    results: List<List<ResultWithClock>>,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): List<List<ActorWithResult>> {
    require(actors.size == results.size) {
        "Different numbers of threads and matching results found (${actors.size} != ${results.size})"
    }
    return actors.mapIndexed { id, threadActors ->
        uniteActorsAndResultsAligned(
            threadActors,
            results[id],
            exceptionStackTraces
        )
    }
}

private fun uniteActorsAndResultsAligned(
    actors: List<Actor>,
    results: List<ResultWithClock>,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): List<ActorWithResult> {
    require(actors.size == results.size) {
        "Different numbers of actors and matching results found (${actors.size} != ${results.size})"
    }
    val actorRepresentations = actors.map { it.toString() }
    val resultRepresentations = results.map { resultRepresentation(it.result, exceptionStackTraces) }
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
    val results: ExecutionResult? = (failure as? IncorrectResultsFailure)?.results
    // If a result is present - collect exceptions stack traces to print them
    val exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace> = results?.let {
        when (val exceptionsProcessingResult = collectExceptionStackTraces(results)) {
            // If some exception was thrown from the Lincheck itself, we ask for bug reporting
            is InternalLincheckBugResult -> {
                appendInternalLincheckBugFailure(exceptionsProcessingResult.exception)
                return this
            }

            is ExceptionStackTracesResult -> exceptionsProcessingResult.exceptionStackTraces
        }
    } ?: emptyMap()

    when (failure) {
        is IncorrectResultsFailure -> appendIncorrectResultsFailure(failure, exceptionStackTraces)
        is DeadlockWithDumpFailure -> appendDeadlockWithDumpFailure(failure)
        is UnexpectedExceptionFailure -> appendUnexpectedExceptionFailure(failure)
        is ValidationFailure -> appendValidationFailure(failure)
        is ObstructionFreedomViolationFailure -> appendObstructionFreedomViolationFailure(failure)
    }
    if (failure.trace != null) {
        appendLine()
        appendLine("= The following interleaving leads to the error =")
        appendTrace(failure.scenario, results, failure.trace, exceptionStackTraces)
        if (failure is DeadlockWithDumpFailure) {
            appendLine()
            append("All threads are in deadlock")
        }
    } else {
        appendExceptionsStackTracesBlock(exceptionStackTraces)
    }
    return this
}

internal fun StringBuilder.appendExceptionsStackTracesBlock(exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>) {
    if (exceptionStackTraces.isNotEmpty()) {
        appendln(EXCEPTIONS_TRACES_TITLE)
        appendExceptionsStackTraces(exceptionStackTraces)
        appendln()
    }
}

internal fun StringBuilder.appendExceptionsStackTraces(exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>): StringBuilder {
    exceptionStackTraces.entries.sortedBy { (_, description) -> description.number }.forEach { (exception, description) ->
        append("#${description.number}: ")

        appendln(exception::class.java.canonicalName)
        description.stackTrace.forEach { appendln("\tat $it") }

        if (description.number < exceptionStackTraces.size) appendln()
    }

    return this
}

fun StringBuilder.appendInternalLincheckBugFailure(exception: Throwable) {
    appendln(
        """
        Wow! You've caught a bug in Lincheck.
        We kindly ask to provide an issue here: https://github.com/JetBrains/lincheck/issues,
        attaching a stack trace printed below and the code that causes the error.
        
        Exception stacktrace:
    """.trimIndent()
    )

    val exceptionRepresentation = StringWriter().use {
        exception.printStackTrace(PrintWriter(it))
        it.toString()
    }
    append(exceptionRepresentation)
}

internal data class ExceptionNumberAndStacktrace(
    /**
     * Serves to match exception in a scenario with its stackTrace
     */
    val number: Int,
    /**
     * Prepared for output stackTrace of this exception
     */
    val stackTrace: List<StackTraceElement>
)

internal fun actorNodeResultRepresentation(result: Result, exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>): String? {
    return when (result) {
        is ExceptionResult -> {
            val exceptionNumberRepresentation = exceptionStackTraces[result.throwable]?.let { " #${it.number}" } ?: ""
            "$result$exceptionNumberRepresentation"
        }
        is VoidResult -> null // don't print
        else -> result.toString()
    }
}

internal fun resultRepresentation(result: Result, exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>): String {
    return when (result) {
        is ExceptionResult -> {
            val exceptionNumberRepresentation = exceptionStackTraces[result.throwable]?.let { " #${it.number}" } ?: ""
            "$result$exceptionNumberRepresentation"
        }

        else -> result.toString()
    }
}

/**
 * Result of collecting exceptions into a map from throwable to its number and stacktrace
 * to use this information to numerate them and print their stacktrace with number.
 * @see collectExceptionStackTraces
 */
private sealed interface ExceptionsProcessingResult

/**
 * Corresponds to the case when we tried to collect exceptions map but found one,
 * that was thrown from Lincheck internally.
 * In that case, we just want to print that exception and don't care about other exceptions.
 */
private data class InternalLincheckBugResult(val exception: Throwable) :
    ExceptionsProcessingResult

/**
 * Result of successful collection exceptions to map when no one of them was thrown from Lincheck.
 */
private data class ExceptionStackTracesResult(val exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>) :
    ExceptionsProcessingResult


/**
 * Collects stackTraces of exceptions thrown during execution
 *
 * This method traverses over all execution results and collects exceptions.
 * For each exception, it also filters stacktrace to cut off all Lincheck-related [StackTraceElement]s.
 * If filtered stackTrace of some exception is empty, then this exception was thrown from Lincheck itself,
 * in that case we return that exception as an internal bug to report it.
 *
 * @return exceptions stack traces map inside [ExceptionStackTracesResult] or [InternalLincheckBugResult]
 * if some exception occurred due a bug in Lincheck itself
 */
private fun collectExceptionStackTraces(executionResult: ExecutionResult): ExceptionsProcessingResult {
    val exceptionStackTraces = mutableMapOf<Throwable, ExceptionNumberAndStacktrace>()

    (executionResult.initResults.asSequence()
            + executionResult.parallelResults.asSequence().flatten()
            + executionResult.postResults.asSequence())
        .filterIsInstance<ExceptionResult>()
        .forEachIndexed { index, exceptionResult ->
            val exception = exceptionResult.throwable

            val filteredStacktrace = exception.stackTrace.takeWhile { LINCHECK_PACKAGE_NAME !in it.className }
            if (filteredStacktrace.isEmpty()) { // Exception in Lincheck itself
                return InternalLincheckBugResult(exception)
            }

            exceptionStackTraces[exception] = ExceptionNumberAndStacktrace(index + 1, filteredStacktrace)
        }

    return ExceptionStackTracesResult(exceptionStackTraces)
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
        stackTraceRepresentation(stackTrace).forEach { appendLine("\t$it") }
    }
    return this
}

private fun StringBuilder.appendIncorrectResultsFailure(
    failure: IncorrectResultsFailure,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): StringBuilder {
    appendln("= Invalid execution results =")
    if (failure.scenario.initExecution.isNotEmpty()) {
        appendln("Init part:")
        appendln(
            uniteActorsAndResultsLinear(
                failure.scenario.initExecution,
                failure.results.initResults,
                exceptionStackTraces
            )
        )
    }
    if (failure.results.afterInitStateRepresentation != null)
        appendln("STATE: ${failure.results.afterInitStateRepresentation}")
    appendln("Parallel part:")
    val parallelExecutionData =
        uniteParallelActorsAndResults(
            failure.scenario.parallelExecution,
            failure.results.parallelResultsWithClock,
            exceptionStackTraces
        )
    appendln(printInColumns(parallelExecutionData))
    if (failure.results.afterParallelStateRepresentation != null) {
        appendln("STATE: ${failure.results.afterParallelStateRepresentation}")
    }
    if (failure.scenario.postExecution.isNotEmpty()) {
        appendln("Post part:")
        appendln(
            uniteActorsAndResultsLinear(
                failure.scenario.postExecution,
                failure.results.postResults,
                exceptionStackTraces
            )
        )
    }
    if (failure.results.afterPostStateRepresentation != null && failure.scenario.postExecution.isNotEmpty()) {
        appendln("STATE: ${failure.results.afterPostStateRepresentation}")
    }

    val hints = mutableListOf<String>()
    if (failure.results.parallelResultsWithClock.flatten().any { !it.clockOnStart.empty }) {
        hints.add(
            """
                Values in "[..]" brackets indicate the number of completed operations
                in each of the parallel threads seen at the beginning of the current operation
            """.trimIndent()
        )
    }
    if (exceptionStackTraces.isNotEmpty()) {
        hints.add(
            """
            The number next to an exception name helps you find its stack trace provided after the interleaving section
        """.trimIndent()
        )
    }
    appendHints(hints)

    return this
}

private fun StringBuilder.appendHints(hints: List<String>) {
    if (hints.isNotEmpty()) {
        appendln(hints.joinToString(prefix = "\n---\n", separator = "\n---\n", postfix = "\n---"))
    }
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

private const val EXCEPTIONS_TRACES_TITLE = "Exception stack traces:"