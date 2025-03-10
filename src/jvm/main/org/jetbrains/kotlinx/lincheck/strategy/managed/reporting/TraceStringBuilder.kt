/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.reporting

import org.jetbrains.kotlinx.lincheck.ExceptionNumberAndStacktrace
import org.jetbrains.kotlinx.lincheck.ExecutionLayout
import org.jetbrains.kotlinx.lincheck.appendExceptionsStackTracesBlock
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.isGeneralPurposeModelCheckingScenario
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.ManagedDeadlockFailure
import org.jetbrains.kotlinx.lincheck.strategy.TimeoutFailure

private const val ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE = "All unfinished threads are in deadlock"

internal class TraceStringBuilder(
    private val stringBuilder: StringBuilder,
    private val failure: LincheckFailure,
    private val results: ExecutionResult,
    private val trace: Trace,
    private val exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>,
) {
    private val nThreads = trace.threadNames.size
    private val threadNames = trace.threadNames
    private val startTraceGraphNode = constructTraceGraph(nThreads, failure, results, trace, exceptionStackTraces)
    
    fun appendTrace(): StringBuilder = stringBuilder.apply {
        if (isGeneralPurposeModelCheckingScenario(failure.scenario)) {
            val (callNode, actorResultNode) = extractLambdaCallOfGeneralPurposeModelChecking(startTraceGraphNode)
            // do not print the method result if it is not expanded
            if (!callNode.shouldBeExpanded(verboseTrace = false) && actorResultNode.resultRepresentation != null) {
                callNode.lastInternalEvent.next = null
            }
            appendShortTrace(listOf(callNode))
            callNode.lastInternalEvent.next = actorResultNode
            appendDetailedTrace(listOf(callNode))
        } else {
            appendShortTrace(startTraceGraphNode)
            appendExceptionsStackTracesBlock(exceptionStackTraces)
            appendDetailedTrace(startTraceGraphNode)
        }
    }
    
    // This is a hack to work around current limitations of the trace representation API
    // to extract the lambda method call on which the general-purpose MC was run.
    // TODO: please refactor me and trace representation API!
    private fun extractLambdaCallOfGeneralPurposeModelChecking(
        startTraceGraphNode: List<TraceNodeOld>
    ): Pair<CallNode, ActorResultNode> {
        val actorNode = startTraceGraphNode.firstOrNull() as? ActorNode
        val callNode = actorNode?.internalEvents?.firstOrNull() as? CallNode
        val actorResultNode = callNode?.lastInternalEvent?.next as? ActorResultNode
        check(actorNode != null)
        check(actorNode.actorRepresentation.startsWith("run"))
        check(actorNode.internalEvents.size == 2)
        check(callNode != null)
        check(actorResultNode != null)
        return callNode to actorResultNode
    }

    /**
     * @param sectionsFirstNodes a list of first nodes in each scenario section
     */
    private fun StringBuilder.appendShortTrace(sectionsFirstNodes: List<TraceNodeOld>) {
        val traceRepresentation = traceGraphToRepresentationList(sectionsFirstNodes, false)
        appendLine(TRACE_TITLE)
        appendTraceRepresentation(traceRepresentation)
        if (failure is ManagedDeadlockFailure || failure is TimeoutFailure) {
            appendLine(ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE)
        }
        appendLine()
    }
    
    /**
     * @param sectionsFirstNodes a list of first nodes in each scenario section
     */
    private fun StringBuilder.appendDetailedTrace(sectionsFirstNodes: List<TraceNodeOld>) {
        appendLine(DETAILED_TRACE_TITLE)
        val traceRepresentationVerbose = traceGraphToRepresentationList(sectionsFirstNodes, true)
        appendTraceRepresentation(traceRepresentationVerbose)
        if (failure is ManagedDeadlockFailure || failure is TimeoutFailure) {
            appendLine(ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE)
        }
    }
    
    private fun StringBuilder.appendTraceRepresentation(traceRepresentation: List<List<TraceEventRepresentation>>) {
        val traceRepresentationSplitted = splitToColumns(traceRepresentation)
        val layout = ExecutionLayout(
            nThreads = nThreads,
            interleavingSections = traceRepresentationSplitted.map { it.columns },
            threadNames = threadNames,
        )
        with(layout) {
            appendSeparatorLine()
            appendHeader()
            appendSeparatorLine()
            traceRepresentationSplitted.forEach { section ->
                appendColumns(section.columns)
                appendSeparatorLine()
            }
        }
    }
    
    /**
     * Convert trace events to the final form of a matrix of strings.
     */
    private fun splitToColumns(traceRepresentation:  List<List<TraceEventRepresentation>>): List<TableSectionColumnsRepresentation> {
        return traceRepresentation.map { sectionRepresentation ->
            val result = List(nThreads) { mutableListOf<String>() }
            for (event in sectionRepresentation) {
                val columnId = event.iThread
                // write message in an appropriate column
                result[columnId].add(event.representation)
                val neededSize = result[columnId].size
                // establish columns size equals
                for (column in result)
                    if (column.size != neededSize)
                        column.add("")
            }
            TableSectionColumnsRepresentation(result)
        }
    }
}