/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.trace

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.ValidationFailure
import org.jetbrains.kotlinx.lincheck.util.AnalysisProfile
import kotlin.math.max

internal typealias SingleThreadedTable<T> = List<SingleThreadedSection<T>>
internal typealias SingleThreadedSection<T> = List<T>

internal typealias MultiThreadedTable<T> = List<MultiThreadedSection<T>>
internal typealias MultiThreadedSection<T> = List<Column<T>>
internal typealias Column<T> = List<T>

@Synchronized // we should avoid concurrent executions to keep `objectNumeration` consistent
internal fun Appendable.appendTrace(
    failure: LincheckFailure,
    results: ExecutionResult,
    trace: Trace,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>,
) {
    TraceReporter(failure, results, trace, exceptionStackTraces).appendTrace(this)
}

/**
 * Appends [Trace] to [Appendable]
 */
internal class TraceReporter(
    private val failure: LincheckFailure,
    results: ExecutionResult,
    trace: Trace,
    private val exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>,
) {
    private val trace = trace.deepCopy()
    private val resultProvider = ExecutionResultsProvider(results, failure, exceptionStackTraces)
    val graph: SingleThreadedTable<TraceNode>
    
    init {
        // Prepares trace by: 
        // - removing validation section (in case of no validation failure)
        // - adding `ActorResult` to actors
        val fixedTrace = trace
            .removeValidationIfNeeded()
            .addResultsToActors()

        graph = traceToCollapsedGraph(fixedTrace, failure.analysisProfile, failure.scenario)
    }
    
    fun appendTrace(app: Appendable) = with(app) {
        // Turn graph into chronological sequence of calls and events, for verbose and simple trace.
        val flattenedShort: SingleThreadedTable<TraceNode> = graph.flattenNodes(ShortTraceFlattenPolicy()).reorder()
        val flattenedVerbose: SingleThreadedTable<TraceNode> = graph.flattenNodes(VerboseTraceFlattenPolicy()).reorder()
        appendTraceTable(TRACE_TITLE, trace, failure, flattenedShort)
        appendLine()
        
        if (!isGeneralPurposeModelCheckingScenario(failure.scenario)) {
            appendExceptionsStackTracesBlock(exceptionStackTraces)
        }
        
        // if empty trace show only the first
        if (flattenedVerbose.sumOf { it.size } != 1) appendTraceTable(DETAILED_TRACE_TITLE, trace, failure, flattenedVerbose)
    }

    /**
     * Adds result info to actor [MethodCallTracePoint]s
     */
    private fun Trace.addResultsToActors(): Trace = this.deepCopy().also {
        it.trace
            .filterIsInstance<MethodCallTracePoint>()
            .filter { it.isActor }
            .forEach { event -> event.returnedValue = resultProvider[event.iThread, event.actorId] }
    }
    
    private fun Trace.removeValidationIfNeeded(): Trace {
        if (failure is ValidationFailure) return this
        val newTrace = this.trace.takeWhile { !(it is SectionDelimiterTracePoint && it.executionPart == ExecutionPart.VALIDATION) }
        return Trace(newTrace, this.threadNames)
    }
}

/**
 * Appends trace table to [Appendable]
 */
internal fun Appendable.appendTraceTable(title: String, trace: Trace, failure: LincheckFailure?, graph: SingleThreadedTable<TraceNode>) {
    appendLine(title)
    val traceRepresentationSplitted = splitInColumns(trace.threadNames.size, graph)
    val stringTable = traceNodeTableToString(traceRepresentationSplitted)
    val layout = ExecutionLayout(
        nThreads = trace.threadNames.size,
        interleavingSections = stringTable,
        threadNames = trace.threadNames,
    )
    with(layout) {
        appendSeparatorLine()
        appendHeader()
        appendSeparatorLine()
        stringTable.forEach { section ->
            appendColumns(section)
            appendSeparatorLine()
        }
    }
    if (failure is ManagedDeadlockFailure || failure is TimeoutFailure) {
        appendLine(ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE)
    }
}

// TODO support multiple root nodes in GPMC mode, needs discussion on how to deal with `result: ...`
private fun removeGPMCLambda(graph: SingleThreadedTable<TraceNode>): SingleThreadedTable<TraceNode> {
    check(graph.size == 1) { "When in GPMC mode only one scenario section is expected" }
    check(graph[0].isNotEmpty()) { "When in GPMC mode atleast one actor is expected (the run() call to be precise)" }
    return graph.map { section -> 
        val first = section.first()
        if (first !is CallNode) return@map section
        if (first.children.isEmpty()) return@map listOf(first.createResultNodeForEmptyActor())
        first.decrementCallDepthOfTree()
        if (first.children.firstOrNull() is CallNode) (first.children.first() as CallNode).tracePoint.returnedValue = first.tracePoint.returnedValue
        first.children + section.drop(1)
    }
}

/**
 * Splits trace into thread columns. Order is maintained.
 * Example of Events `E1 - E3` on threads t1 -t3`
 * ```
 * | E1(t1) |          | E1(t1) |        |        |
 * | E2(t3) |    -->   |        |        | E2(t3) |
 * | E3(t2) |          |        | E3(t2) |        |
 * ```
 */
private fun splitInColumns(nThreads: Int, flattened: SingleThreadedTable<TraceNode>): MultiThreadedTable<TraceNode?> =
    flattened.map { section ->
        val multiThreadedSection = List<MutableList<TraceNode?>>(nThreads) { mutableListOf() }
        section.forEach { node -> 
            repeat(nThreads) { i -> 
                if (i == node.iThread) multiThreadedSection[i].add(node)
                else multiThreadedSection[i].add(null)
            }
        }
        multiThreadedSection
    }

private const val NO_SPIN_CYCLE = -1
private const val START_SPIN_CYCLE = -2
// TODO bugfix for spin cycle start points not having the lowest indent up to switch event
/**
 * Prints all cells of the [MultiThreadedTable] to string representation.
 * Prepends spin cycle visualization where needed.
 */
private fun traceNodeTableToString(table: MultiThreadedTable<TraceNode?>): MultiThreadedTable<String> = 
    table.map tableMap@{ section -> section.map sectionMap@{ column -> 
        var spinCycleDepth = NO_SPIN_CYCLE
        var additionalSpace = column
            .firstOrNull { it is EventNode && it.tracePoint is SpinCycleStartTracePoint }
            ?.let { max(0, 2 - it.callDepth) } ?: 0
                
        // TraceNode to string        
        column.map { node ->
            if (node == null) return@map ""
            val virtualCallDepth = additionalSpace + node.callDepth
            val virtualSpinCycleDepth = additionalSpace + spinCycleDepth
            
            // If begin of spin cycle
            if (spinCycleDepth == START_SPIN_CYCLE) {
                spinCycleDepth = node.callDepth
                return@map "  ".repeat(virtualCallDepth - 2) + "┌╶> " + node.toString()
            }
            
            // If spinc cycle detected change state. Next iteration will start visualization
            if (node is EventNode && node.tracePoint is SpinCycleStartTracePoint) spinCycleDepth = START_SPIN_CYCLE

            // If end of spin cycle
            if (spinCycleDepth >= 0 && node is EventNode
                && (node.tracePoint is ObstructionFreedomViolationExecutionAbortTracePoint || node.tracePoint is SwitchEventTracePoint)) {
                spinCycleDepth = NO_SPIN_CYCLE
                val prefix = "  ".repeat(virtualSpinCycleDepth - 2) + "└╶╶╶" + "╶╶".repeat(max(virtualCallDepth - virtualSpinCycleDepth, 0)) 
                return@map  prefix.dropLast(1) + " " + node.toString()
            }
            
            // If during spin cycle
            if (spinCycleDepth >= 0) {
                return@map "  ".repeat(virtualSpinCycleDepth - 2) + "|   " + "  ".repeat(max(virtualCallDepth - virtualSpinCycleDepth, 0)) + node.toString()
            }
            
            // Default
            return@map "  ".repeat(virtualCallDepth) + node.toString()
        }
    }
}


/**
 * Helper class to provider execution results, including a validation function result
 */
private class ExecutionResultsProvider(
    result: ExecutionResult?, 
    val failure: LincheckFailure,
    val exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>,
) {

    /**
     * A map of type Map<(threadId, actorId) -> Result>
     */
    val threadNumberToActorResultMap: Map<Pair<Int, Int>, Result?>

    init {
        val results = hashMapOf<Pair<Int, Int>, Result?>()
        if (result != null) {
            results += result.threadsResults
                .flatMapIndexed { tId, actors -> actors.flatMapIndexed { actorId, result ->
                    listOf((tId to actorId) to result)
                }}
                .toMap()
        }
        if (failure is ValidationFailure) {
            results[0 to firstThreadActorCount(failure)] = ExceptionResult.create(failure.exception)
        }
        threadNumberToActorResultMap = results
    }

    operator fun get(iThread: Int, actorId: Int): ReturnedValueResult.ActorResult {
        return actorNodeResultRepresentation(threadNumberToActorResultMap[iThread to actorId])
    }

    private fun firstThreadActorCount(failure: ValidationFailure): Int =
        failure.scenario.initExecution.size + failure.scenario.parallelExecution[0].size + failure.scenario.postExecution.size
    
    private fun actorNodeResultRepresentation(
        result: Result?,
    ): ReturnedValueResult.ActorResult {
        // We don't mark actors that violated obstruction freedom as hung.
        if (result == null && failure is ObstructionFreedomViolationFailure) return ReturnedValueResult.ActorResult("", false, false, false)
        return when (result) {
            null -> ReturnedValueResult.ActorResult("<hung>", true, false, true)
            is ExceptionResult -> {
                val excNumber = exceptionStackTraces[result.throwable]?.number ?: -1
                val exceptionNumberRepresentation = exceptionStackTraces[result.throwable]?.let { " #${it.number}" } ?: ""
                ReturnedValueResult.ActorResult("$result$exceptionNumberRepresentation", true, true, false, excNumber)
            }
            is VoidResult -> ReturnedValueResult.ActorResult("void", false, true, false)
            else -> ReturnedValueResult.ActorResult(result.toString(), true, true, false)
        }
    }

}

internal fun traceToCollapsedGraph(trace: Trace, analysisProfile: AnalysisProfile, scenario: ExecutionScenario?): SingleThreadedTable<TraceNode> {
    // Turn trace into graph which is List of sections. Where a section is a list of rootNodes (actors).
    val traceGraph = traceToGraph(trace)

    // Optimizes trace by combining trace points for synthetic field accesses etc..
    val compressedTraceGraph = traceGraph
        .compressTrace()
        .collapseLibraries(analysisProfile)

    return if (scenario != null && isGeneralPurposeModelCheckingScenario(scenario)) removeGPMCLambda(compressedTraceGraph) else compressedTraceGraph
}

internal const val ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE = "All unfinished threads are in deadlock"
internal const val TRACE_TITLE = "The following interleaving leads to the error:"
internal const val DETAILED_TRACE_TITLE = "Detailed trace:"
