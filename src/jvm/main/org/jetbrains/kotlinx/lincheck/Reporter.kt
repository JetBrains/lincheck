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

import sun.nio.ch.lincheck.TestThread
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.trace.appendTrace
import org.jetbrains.kotlinx.lincheck.util.LoggingLevel
import org.jetbrains.kotlinx.lincheck.util.LoggingLevel.*
import java.io.*
import kotlin.math.max
import kotlin.reflect.jvm.javaMethod

class Reporter(private val logLevel: LoggingLevel) {
    private val out: PrintStream = System.out
    private val outErr: PrintStream = System.err

    fun logIteration(iteration: Int, maxIterations: Int, scenario: ExecutionScenario) = log(INFO) {
        appendLine("\n= Iteration $iteration / $maxIterations =")
        appendExecutionScenario(scenario)
    }

    fun logFailedIteration(failure: LincheckFailure, loggingLevel: LoggingLevel = INFO) = log(loggingLevel) {
        appendFailure(failure)
    }

    fun logScenarioMinimization(scenario: ExecutionScenario) = log(INFO) {
        appendLine("\nInvalid interleaving found, trying to minimize the scenario below:")
        appendExecutionScenario(scenario)
    }

    private inline fun log(logLevel: LoggingLevel, crossinline msg: Appendable.() -> Unit): Unit = synchronized(this) {
        if (this.logLevel > logLevel) return
        val sb = StringBuilder()
        msg(sb)
        val output = if (logLevel == WARN) outErr else out
        output.println(sb)
    }
}

/**
 * Appends a string representing list of columns as a table.
 * The columns of the table are separated by the vertical bar symbol `|`.
 *
 * @param data list of columns of the table.
 * @param columnWidths minimum widths of columns,
 *   if not specified then by default a length of the longest string in each column is used.
 * @param transform a function to convert data elements to strings,
 *   [toString] method is used by default.
 */
internal fun <T> Appendable.appendColumns(
    data: List<List<T>>,
    columnWidths: List<Int>? = null,
    transform: ((T) -> String)? = null
) {
    require(columnWidths == null || columnWidths.size == data.size)
    val nCols = data.size
    val nRows = data.maxOfOrNull { it.size } ?: 0
    val strings = data.map { col -> col.map {
        transform?.invoke(it) ?: it.toString()
    }}
    val colsWidth = columnWidths ?: strings.map { col ->
        col.maxOfOrNull { it.length } ?: 0
    }
    val table = (0 until nRows).map { iRow -> (0 until nCols).map { iCol ->
        strings[iCol].getOrNull(iRow).orEmpty().padEnd(colsWidth[iCol])
    }}

    table.forEach {
        appendLine(it.joinToString(separator = " | ", prefix = "| ", postfix = " |"))
    }
}

/**
 * A class representing tabular layout to append tabular data to [PrintStream].
 *
 * @param columnNames names of columns of the table.
 * @param columnWidths minimum widths of columns.
 * @param columnHeaderCentering a flag enabling/disabling centering of column names.
 */
internal class TableLayout(
    columnNames: List<String>,
    columnWidths: List<Int>,
    columnHeaderCentering: Boolean = true,
) {
    init {
        require(columnNames.size == columnWidths.size)
    }

    val columnWidths = columnWidths.mapIndexed { i, col ->
        col.coerceAtLeast(columnNames[i].length)
    }

    val columnNames = if (columnHeaderCentering) {
        columnNames.mapIndexed { i, name ->
            val padding = this.columnWidths[i] - name.length
            val leftPadding = padding / 2
            val rightPadding = padding / 2 + padding % 2
            " ".repeat(leftPadding) + name + " ".repeat(rightPadding)
        }
    } else columnNames

    val nColumns
        get() = columnNames.size

    private val lineSize = this.columnWidths.sum() + " | ".length * (nColumns - 1)
    private val separator = "| " + "-".repeat(lineSize) + " |"

    /**
     * Appends a horizontal separating line of the format `| ----- |`.
     */
    fun Appendable.appendSeparatorLine() = apply {
        appendLine(separator)
    }

    /**
     * Appends a single line wrapped by `|` symbols to fit into table borders.
     */
    fun Appendable.appendWrappedLine(line: String) = apply {
        appendLine("| " + line.padEnd(lineSize) + " |")
    }

    /**
     * Appends columns.
     */
    fun<T> Appendable.appendColumns(data: List<List<T>>, transform: ((T) -> String)? = null) = apply {
        require(data.size == nColumns)
        appendColumns(data, columnWidths, transform)
    }

    /**
     * Appends the first column.
     */
    fun <T> Appendable.appendToFirstColumn(data: T) = apply {
        val columns = listOf(listOf(data)) + List(columnWidths.size - 1) { emptyList() }
        appendColumns(columns, columnWidths, transform = null)
    }

    /**
     * Appends a single column, all other columns are filled blank.
     *
     * @param iCol index of the appended column.
     * @param data appended column.
     * @param transform a function to convert data elements to strings,
     *   [toString] method is used by default.
     */
    fun <T> Appendable.appendColumn(iCol: Int, data: List<T>, transform: ((T) -> String)? = null) = apply {
        val cols = (0 until nColumns).map { i ->
            if (i == iCol) data else listOf()
        }
        appendColumns(cols, transform)
    }

    /**
     * Appends a single row.
     *
     * @param data appended row.
     * @param transform a function to convert data elements to strings,
     *   [toString] method is used by default.
     */
    fun<T> Appendable.appendRow(data: List<T>, transform: ((T) -> String)? = null) = apply {
        require(data.size == nColumns)
        val strings = data
            .map { transform?.invoke(it) ?: it.toString() }
            .mapIndexed { i, str -> str.padEnd(columnWidths[i]) }
        appendLine(strings.joinToString(separator = " | ", prefix = "| ", postfix = " |"))
    }

    /**
     * Appends a header row containing names of columns.
     */
    fun Appendable.appendHeader() = apply {
        appendRow(columnNames)
    }

}

/**
 * Table layout for appending execution data (e.g. execution scenario, results, etc).
 */
internal fun ExecutionLayout(
    initPart: List<String>,
    parallelPart: List<List<String>>,
    postPart: List<String>,
    validationFunctionName: String?
): TableLayout {
    val size = max(1, parallelPart.size)
    val threadHeaders = (0 until size).map { "Thread ${it + 1}" }
    val firstThreadNonParallelParts = initPart + postPart + (validationFunctionName?.let { listOf(it) } ?: emptyList())
    val columnsContent = parallelPart.map { it.toMutableList() }.toMutableList()

    if (columnsContent.isNotEmpty()) {
        // we don't care about the order as we just want to find the longest string
        columnsContent.first() += firstThreadNonParallelParts
    } else {
        // if the parallel part is empty, we need to add the first column
        columnsContent += firstThreadNonParallelParts.toMutableList()
    }
    val columnWidths = columnsContent.map { column -> column.maxOfOrNull { it.length } ?: 0 }

    return TableLayout(threadHeaders, columnWidths)
}

/**
 * Table layout for interleaving.
 *
 * @param interleavingSections list of sections.
 *   Each section is represented by a list of columns related to threads.
 *   Must be not empty, that is, contain at least one section.
 */
internal fun ExecutionLayout(
    nThreads: Int,
    interleavingSections: List<List<List<String>>>,
    threadNames: List<String>? = null,
): TableLayout {
    val columnWidths = MutableList(nThreads) { 0 }
    val threadHeaders = threadNames ?: (0 until nThreads).map { "Thread ${it + 1}" }
    interleavingSections.forEach { section ->
        section.mapIndexed { columnIndex, actors ->
            val maxColumnActorLength = actors.maxOf { it.length }
            columnWidths[columnIndex] = max(columnWidths[columnIndex], maxColumnActorLength)
        }
    }
    return TableLayout(threadHeaders, columnWidths)
}

internal fun Appendable.appendExecutionScenario(
    scenario: ExecutionScenario,
    showValidationFunctions: Boolean = false
): Appendable {
    val initPart = scenario.initExecution.map(Actor::toString)
    val postPart = scenario.postExecution.map(Actor::toString)
    val parallelPart = scenario.parallelExecution.map { it.map(Actor::toString) }
    val validationFunctionName = if (showValidationFunctions) scenario.validationFunction?.let { "${it.method.name}()" } else null
    with(ExecutionLayout(initPart, parallelPart, postPart, validationFunctionName)) {
        appendSeparatorLine()
        appendHeader()
        appendSeparatorLine()
        if (initPart.isNotEmpty()) {
            appendColumn(0, initPart)
            appendSeparatorLine()
        }
        appendColumns(parallelPart)
        appendSeparatorLine()
        if (postPart.isNotEmpty()) {
            appendColumn(0, postPart)
            appendSeparatorLine()
        }
        if (validationFunctionName != null) {
            appendToFirstColumn(validationFunctionName)
            appendSeparatorLine()
        }
    }
    return this
}

private fun<T, U> requireEqualSize(x: List<T>, y: List<U>, lazyMessage: () -> String) {
    require(x.size == y.size) { "${lazyMessage()} (${x.size} != ${y.size})" }
}

internal fun Appendable.appendExecutionScenarioWithResults(
    failure: LincheckFailure,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>,
): Appendable {
    val scenario = failure.scenario
    val executionResult = failure.results
    requireEqualSize(scenario.parallelExecution, executionResult.parallelResults) {
        "Different numbers of threads and matching results found"
    }
    requireEqualSize(scenario.initExecution, executionResult.initResults) {
        "Different numbers of actors and matching results found"
    }
    requireEqualSize(scenario.postExecution, executionResult.postResults) {
        "Different numbers of actors and matching results found"
    }
    val (initPart, parallelPart, postPart, validationFunctionName, hasClocks) = executionResultsRepresentation(failure, exceptionStackTraces)
    with(ExecutionLayout(initPart, parallelPart, postPart, validationFunctionName)) {
        appendSeparatorLine()
        appendHeader()
        appendSeparatorLine()
        if (initPart.isNotEmpty()) {
            appendColumn(0, initPart)
            appendSeparatorLine()
        }
        if (executionResult.afterInitStateRepresentation != null) {
            appendWrappedLine("STATE: ${executionResult.afterInitStateRepresentation}")
            appendSeparatorLine()
        }
        if (parallelPart.isNotEmpty()) {
            appendColumns(parallelPart)
            appendSeparatorLine()
        }
        if (executionResult.afterParallelStateRepresentation != null) {
            appendWrappedLine("STATE: ${executionResult.afterParallelStateRepresentation}")
            appendSeparatorLine()
        }
        if (postPart.isNotEmpty()) {
            appendColumn(0, postPart)
            appendSeparatorLine()
        }
        if (executionResult.afterPostStateRepresentation != null && postPart.isNotEmpty()) {
            appendWrappedLine("STATE: ${executionResult.afterPostStateRepresentation}")
            appendSeparatorLine()
        }
        if (validationFunctionName != null) {
            appendToFirstColumn(validationFunctionName)
            appendSeparatorLine()
        }
    }
    val hints = mutableListOf<String>()
    if (scenario.initExecution.isNotEmpty() || scenario.postExecution.isNotEmpty()) {
        hints.add(
            """
                All operations above the horizontal line | ----- | happen before those below the line
            """.trimIndent()
        )
    }
    if (hasClocks) {
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

internal fun Appendable.appendFailure(failure: LincheckFailure): Appendable {
    val results: ExecutionResult = failure.results
    // If a result is present - collect exceptions stack traces to print them
    val exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace> = results.let {
        when (val exceptionsProcessingResult = collectExceptionStackTraces(results)) {
            // If some exception was thrown from the Lincheck itself, we ask for bug reporting
            is InternalLincheckBugResult -> {
                appendInternalLincheckBugFailure(exceptionsProcessingResult.exception)
                return this
            }

            is ExceptionStackTracesResult -> exceptionsProcessingResult.exceptionStackTraces
        }
    }

    if (isGeneralPurposeModelCheckingScenario(failure.scenario)) {
        check(exceptionStackTraces.size <= 1)
        if (exceptionStackTraces.isNotEmpty()) {
            val (exception, descriptor) = exceptionStackTraces.entries.single()
            appendLine(GENERAL_PURPOSE_MODEL_CHECKING_FAILURE_TITLE)
            appendLine()
            appendExceptionStackTrace(exception, descriptor.stackTrace)
        } else {
            appendLine(GENERAL_PURPOSE_MODEL_CHECKING_HUNG_TITLE)
        }
        if (failure.trace != null) {
            appendLine()
            appendTrace(failure, results, failure.trace, exceptionStackTraces)
        }
        return this
    }

    when (failure) {
        is IncorrectResultsFailure -> appendIncorrectResultsFailure(failure, exceptionStackTraces)
        is TimeoutFailure -> appendTimeoutDeadlockWithDumpFailure(failure, exceptionStackTraces)
        is UnexpectedExceptionFailure -> appendUnexpectedExceptionFailure(failure, exceptionStackTraces)
        is ValidationFailure -> when (failure.exception) {
            is LincheckInternalBugException -> appendInternalLincheckBugFailure(failure.exception)
            else ->  appendValidationFailure(failure, exceptionStackTraces)
        }
        is ObstructionFreedomViolationFailure -> appendObstructionFreedomViolationFailure(failure, exceptionStackTraces)
        is ManagedDeadlockFailure -> appendManagedDeadlockWithDumpFailure(failure, exceptionStackTraces)
    }
    if (failure.trace != null) {
        appendLine()
        appendTrace(failure, results, failure.trace, exceptionStackTraces)
    } else {
        appendExceptionsStackTracesBlock(exceptionStackTraces)
    }
    return this
}

internal fun isGeneralPurposeModelCheckingScenario(scenario: ExecutionScenario): Boolean {
    val actor = scenario.parallelExecution.getOrNull(0)?.getOrNull(0)
    return (actor?.method == GeneralPurposeModelCheckingWrapper::runGPMCTest.javaMethod)
}

private data class ExecutionResultsRepresentationData(
    val initPart: List<String>,
    val parallelPart: List<List<String>>,
    val postPart: List<String>,
    val validationFunctionRepresentation: String?,
    val hasClocks: Boolean
)

private fun executionResultsRepresentation(
    failure: LincheckFailure,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): ExecutionResultsRepresentationData {
    val results = failure.results
    val scenario = failure.scenario

    val initActorData = results.initResults.zip(scenario.initExecution).map { (result, actor) ->
        ResultActorData(0, actor, result, exceptionStackTraces, null)
    }
    val isIncorrectResultsFailure = failure is IncorrectResultsFailure
    var hasClock = false
    val parallelActorData = scenario.parallelExecution.mapIndexed { threadId, actors ->
        actors.zip(results.parallelResultsWithClock[threadId]) { actor, resultWithClock ->
            val hbClock = if (isIncorrectResultsFailure) resultWithClock.clockOnStart else null
            if (hbClock != null && !hbClock.isEmpty(threadId)) {
                hasClock = true
            }
            ResultActorData(threadId, actor, resultWithClock.result, exceptionStackTraces, hbClock)
        }
    }
    hasClock = isIncorrectResultsFailure && hasClock
    val postActorData = results.postResults.zip(scenario.postExecution).map { (result, actor) ->
        ResultActorData(0, actor, result, exceptionStackTraces, null)
    }
    var executionHung: Boolean
    val (initialExecutionHung, initialActorRepresentation) = executionResultsRepresentation(initActorData, failure)
    executionHung = initialExecutionHung

    val parallelActorRepresentation = if (executionHung) emptyList() else {
        parallelActorData.map { actors ->
            val (threadExecutionHung, representation) = executionResultsRepresentation(actors, failure)
            executionHung = executionHung || threadExecutionHung
            representation
        }
    }

    val postActorRepresentation = if (executionHung) emptyList() else executionResultsRepresentation(postActorData, failure).second

    val validationFunctionName = if (failure is ValidationFailure) {
        scenario.validationFunction?.let { "${it.method.name}(): ${failure.exception::class.simpleName}" }
    } else null

    return ExecutionResultsRepresentationData(
        initPart = initialActorRepresentation,
        parallelPart = parallelActorRepresentation,
        postPart = postActorRepresentation,
        validationFunctionRepresentation = validationFunctionName,
        hasClocks = hasClock
    )
}


private data class ResultActorData(
    val threadId: Int,
    val actor: Actor,
    val result: Result?,
    val exceptionInfo: ExceptionNumberAndStacktrace? = null,
    val hbClock: HBClock? = null
) {
    constructor(threadId: Int, actor: Actor, result: Result?, exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>, hbClock: HBClock?)
            : this(threadId, actor, result, (result as? ExceptionResult)?.let { exceptionStackTraces[it.throwable] }, hbClock)

    override fun toString(): String {
        return "${actor}${result.toString().let { ": $it" }}" +
                (exceptionInfo?.let { " #${it.number}" } ?: "") +
                (hbClock?.takeIf { !it.isEmpty(threadId) }?.let { " $it" } ?: "")
    }
}

/**
 * Composes actor results representation.
 *
 * @return a pair of a flag, indicating if execution has hung on one of these actors,
 * and a representations of non-null present results.
 * If some actors have hung, the first of them
 * is represented as <hung>, others are ignored.
 */
private fun executionResultsRepresentation(results: List<ResultActorData>, failure: LincheckFailure): Pair<Boolean, List<String>> {
    val representation = mutableListOf<String>()
    for (actorWithResult in results) {
        if (actorWithResult.result == null) {
            // We don't mark actors that violated obstruction freedom as hung.
            if (failure is ObstructionFreedomViolationFailure) {
                representation += "${actorWithResult.actor}"
                return true to representation
            }
            representation += "${actorWithResult.actor}: <hung>"
            return true to representation
        }
        representation += actorWithResult.toString()
    }

    return false to representation
}

internal fun Appendable.appendExceptionsStackTracesBlock(exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>) {
    if (exceptionStackTraces.isNotEmpty()) {
        appendLine(EXCEPTIONS_TRACES_TITLE)
        appendExceptionsStackTraces(exceptionStackTraces)
        appendLine()
    }
}

internal fun Appendable.appendExceptionStackTrace(exception: Throwable, stackTrace: List<StackTraceElement>): Appendable {
    appendLine(exception::class.java.canonicalName + ": " + exception.message)
    stackTrace.forEach { appendLine("\tat $it") }
    return this
}

internal fun Appendable.appendExceptionsStackTraces(exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>): Appendable {
    exceptionStackTraces.entries.sortedBy { (_, description) -> description.number }.forEach { (exception, description) ->
        append("#${description.number}: ")

        appendLine(exception::class.java.canonicalName + ": " + exception.message)
        description.stackTrace.forEach { appendLine("\tat $it") }

        if (description.number < exceptionStackTraces.size) appendLine()
    }

    return this
}

private fun Appendable.appendInternalLincheckBugFailure(
    exception: Throwable,
) {
    appendLine(
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
internal sealed interface ExceptionsProcessingResult

/**
 * Corresponds to the case when we tried to collect exceptions map but found one,
 * that was thrown from Lincheck internally.
 * In that case, we just want to print that exception and don't care about other exceptions.
 */
internal data class InternalLincheckBugResult(val exception: Throwable) :
    ExceptionsProcessingResult

/**
 * Result of successful collection exceptions to map when no one of them was thrown from Lincheck.
 */
internal data class ExceptionStackTracesResult(val exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>) :
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
internal fun collectExceptionStackTraces(executionResult: ExecutionResult): ExceptionsProcessingResult {
    val exceptionStackTraces = mutableMapOf<Throwable, ExceptionNumberAndStacktrace>()
    executionResult.allResults
        .filterIsInstance<ExceptionResult>()
        .forEachIndexed { index, exceptionResult ->
            val exception = exceptionResult.throwable
            if (exception.isInternalLincheckBug()) {
                return InternalLincheckBugResult(exception)
            }
            val stackTrace = exception.stackTrace
                // filter lincheck methods
                .filter { !isInLincheckPackage(it.className) }
            exceptionStackTraces[exception] = ExceptionNumberAndStacktrace(index + 1, stackTrace)
        }
    return ExceptionStackTracesResult(exceptionStackTraces)
}

private fun Throwable.isInternalLincheckBug(): Boolean {
    // we expect every Lincheck test thread to start from the Lincheck runner routines,
    // so we filter out stack trace elements of these runner routines
    val testStackTrace = stackTrace.takeWhile { LINCHECK_RUNNER_PACKAGE_NAME !in it.className }
    // collect Lincheck functions from the stack trace
    val lincheckStackFrames = testStackTrace.filter { isInLincheckPackage(it.className) }
    // special handling of `cancelByLincheck` primitive and general purpose model checking function call
    val lincheckLegalStackFrames = listOf("cancelByLincheck", "runGPMCTest")
    if (lincheckStackFrames.all { it.methodName in lincheckLegalStackFrames }) {
        return false
    }
    // otherwise, if the stack trace contains any Lincheck functions, we classify it as a Lincheck bug
    return lincheckStackFrames.isNotEmpty()
}

private fun Appendable.appendUnexpectedExceptionFailure(
    failure: UnexpectedExceptionFailure,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): Appendable {
    appendLine("= The execution failed with an unexpected exception =")
    appendExecutionScenarioWithResults(failure, exceptionStackTraces)
    appendLine()
    appendException(failure.exception)
    return this
}

private fun Appendable.appendManagedDeadlockWithDumpFailure(
    failure: LincheckFailure,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): Appendable {
    appendLine("= The execution has hung =")
    appendExecutionScenarioWithResults(failure, exceptionStackTraces)
    appendLine()
    return this
}

private fun Appendable.appendTimeoutDeadlockWithDumpFailure(
    failure: TimeoutFailure,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): Appendable {
    appendLine("= The execution has hung, see the thread dump =")
    appendExecutionScenarioWithResults(failure, exceptionStackTraces)
    appendLine()
    // Sort threads to produce same output for the same results
    @Suppress("DEPRECATION") // Thread.id
    for ((t, stackTrace) in failure.threadDump.entries.sortedBy { it.key.id }) {
        val threadNumber = (t as? TestThread)?.name ?: "?"
        appendLine("Thread-$threadNumber:")
        stackTrace.map {
            StackTraceElement(
                /* declaringClass = */ it.className,
                /* methodName = */ it.methodName,
                /* fileName = */ it.fileName,
                /* lineNumber = */ it.lineNumber
            )
        }.run {
            // Remove all the Lincheck internals only if the program
            // has hung in the user code. Otherwise, print the full
            // stack trace for easier debugging.
            if (isEmpty() || first().isLincheckInternals) {
                this
            } else {
                filter { !it.isLincheckInternals }
            }
        }.forEach { appendLine("\t$it") }
    }
    return this
}

private val StackTraceElement.isLincheckInternals get() =
    this.className.startsWith("org.jetbrains.kotlinx.lincheck.")

private fun Appendable.appendIncorrectResultsFailure(
    failure: IncorrectResultsFailure,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>,
): Appendable {
    appendLine("= Invalid execution results =")
    appendExecutionScenarioWithResults(failure, exceptionStackTraces)
    return this
}

private fun Appendable.appendHints(hints: List<String>) {
    if (hints.isNotEmpty()) {
        appendLine(hints.joinToString(prefix = "\n---\n", separator = "\n---\n", postfix = "\n---"))
    }
}

private fun Appendable.appendValidationFailure(
    failure: ValidationFailure,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): Appendable {
    appendLine("= Validation function ${failure.validationFunctionName} has failed =")
    appendExecutionScenarioWithResults(failure, exceptionStackTraces)
    appendLine()
    appendLine()
    appendException(failure.exception)
    return this
}

private fun Appendable.appendObstructionFreedomViolationFailure(
    failure: ObstructionFreedomViolationFailure,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): Appendable {
    appendLine("= ${failure.reason} =")
    appendExecutionScenarioWithResults(failure, exceptionStackTraces)
    return this
}

private fun Appendable.appendException(t: Throwable) {
    val sw = StringWriter()
    t.printStackTrace(PrintWriter(sw))
    appendLine(sw.toString())
}

private const val GENERAL_PURPOSE_MODEL_CHECKING_FAILURE_TITLE  = "= Concurrent test failed ="
private const val GENERAL_PURPOSE_MODEL_CHECKING_HUNG_TITLE     = "= Concurrent test has hung ="

private const val EXCEPTIONS_TRACES_TITLE = "Exception stack traces:"
