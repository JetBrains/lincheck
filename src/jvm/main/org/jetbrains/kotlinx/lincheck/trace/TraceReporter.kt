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
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.ValidationFailure
import org.jetbrains.lincheck.util.*
import kotlin.math.max

internal typealias SingleThreadedTable<T> = Column<T>
internal typealias MultiThreadedTable<T> = List<Column<T>>
internal typealias Column<T> = List<T>

/**
 * Appends [Trace] to [Appendable]
 */
internal class TraceReporter(
    private val trace: Trace,
    analysisProfile: AnalysisProfile,
) {
    val tree: MultiThreadedTable<TraceNode> =
        traceToCollapsedTree(this.trace, analysisProfile)

    fun appendTrace(appendable: Appendable, verbose: Boolean) = with(appendable) {
        appendTraceTable(trace.threadNames, tree.map { it.reorder() } , verbose)
    }
}

/**
 * Appends trace table to [Appendable]
 */
internal fun Appendable.appendTraceTable(threadNames: List<String>, tree: MultiThreadedTable<TraceNode>, verbose: Boolean) {
    val sections = tree.splitIntoSections().map { threads ->
        threads
            .toTraceLinesTable(verbose)
            .flatten()
            .sortedBy { it.eventNumber }
            .splitIntoColumns(threads.size)
    }
    val layout = ExecutionLayout(
        nThreads = threadNames.size,
        interleavingSections = sections,
        threadNames = threadNames,
    )
    with(layout) {
        appendSeparatorLine()
        appendHeader()
        appendSeparatorLine()
        sections.forEach { section ->
            appendColumns(section)
            appendSeparatorLine()
        }
    }
}

/**
 * Splits list of trace lines into thread columns, preserving the order of events.
 *
 * Example (`e1, e2, e3` - events, `t1, t2, t3` - threads):
 * ```
 * | t1: e1 |          | t1:e1 |        |       |
 * | t3: e2 |    -->   |       |        | t3:e2 |
 * | t2: e3 |          |       | t2: e3 |       |
 * ```
 */
private fun List<TraceLine>.splitIntoColumns(threadCount: Int): MultiThreadedTable<String> {
    val lines = this
    val table = List(threadCount) { mutableListOf<String>() }
    for (line in lines) {
        for (threadId in table.indices) {
            if (threadId == line.threadId) {
                table[threadId].add(line.string)
            } else {
                table[threadId].add("")
            }

        }
    }
    return table
}

/**
 * Splits a single threaded table of trace nodes into multiple sections
 * based on placement of `SectionDelimiterTracePoint` trace points in the table.
 */
private fun MultiThreadedTable<TraceNode>.splitIntoSections(): List<MultiThreadedTable<TraceNode>> {
    val sections = mutableListOf<MultiThreadedTable<TraceNode>>()

    // we assume sections' marks can only appear in the first thread
    val threads = this
    val nodes = threads[0]

    // Collect contiguous ranges between section delimiters as we iterate indices
    data class ExecutionPartRange(val part: ExecutionPart, val range: IntRange)
    val partRanges = mutableListOf<ExecutionPartRange>()

    var i = 0
    while (i < nodes.size) {
        val start = nodes.indexOf(from = i) { it.tracePoint is SectionDelimiterTracePoint }
            .takeIf { it != -1 } ?: break
        val end = nodes.indexOf(from = start + 1) { it.tracePoint is SectionDelimiterTracePoint }
            .takeIf { it != -1 } ?: nodes.size
        partRanges += ExecutionPartRange(
            part = (nodes[start].tracePoint as SectionDelimiterTracePoint).executionPart,
            range = IntRange(start + 1, end)
        )
        i = end
    }

    // Validate that execution parts appear in expected order.
    if (partRanges.isNotEmpty()) {
        val parts = partRanges.map { it.part }
        check(parts.count { it == ExecutionPart.INIT } <= 1) {
            "Expected at most one INIT section delimiter"
        }
        check(parts.count { it == ExecutionPart.PARALLEL } == 1) {
            "Expected exactly one PARALLEL section delimiter"
        }
        check(parts.count { it == ExecutionPart.POST } <= 1) {
            "Expected at most one POST section delimiter"
        }
        check(parts.count { it == ExecutionPart.VALIDATION } <= 1) {
            "Expected at most one VALIDATION section delimiter"
        }
        check(parts.isSortedBy { it.ordinal }) {
            "Expected section delimiters to be in the following order: INIT, PARALLEL, POST, VALIDATION," +
            "but got: $parts"
        }
    }

    for (partRange in partRanges) {
        val firstThread = nodes.subList(partRange.range.first, partRange.range.last)
        if (partRange.part == ExecutionPart.PARALLEL) {
            sections += (listOf(firstThread) + threads.subList(1, threads.size))
        } else {
            sections += (listOf(firstThread) + List(threads.size - 1) { emptyList() })
        }
    }
    // No sections found => add a single section consisting of all threads
    if (sections.isEmpty()) sections.add(threads)

    return sections
}

/**
 * Maps all trace nodes of the [MultiThreadedTable] to their string representation.
 *
 * - Unfolds all nested trace nodes where needed.
 * - Prepends spin cycle visualization where needed.
 */
private fun MultiThreadedTable<TraceNode>.toTraceLinesTable(verbose: Boolean = true): MultiThreadedTable<TraceLine> {
    return this.map { nodes ->
        val filter = if (verbose) VerboseTraceFilter() else ShortenTraceFilter()
        val columnPrinter = TraceColumnPrinter(filter, verbose)
        val nodes = nodes.filterNot { filter.shouldFilter(it.tracePoint) }
        // first iterate through all top-level nodes to calculate additional padding (if required)
        nodes.forEach { node ->
            columnPrinter.updateAdditionalPaddingWidth(node)
        }
        // then iterate through all top-level nodes again to print them and their children recursively
        nodes.forEach { node ->
            columnPrinter.appendTraceNode(node)
        }
        columnPrinter.lines
    }
}

internal class TraceLine(
    val eventNumber: Int,
    val threadId: Int,
    val string: String,
) {
    companion object {
        val EMPTY = TraceLine(-1, -1, "")
    }
}

private class TraceColumnPrinter(
    val filter: TraceFilter? = null,
    val verbose: Boolean = true,
) {
    private val _lines: MutableList<TraceLine> = mutableListOf()
    val lines: List<TraceLine> get() = _lines

    private var callStack = mutableListOf<TraceNode>()
    private val callDepth get() = callStack.size

    private var spinCycleState: SpinCycleState? = null
    private var spinCycleDepth: Int = -1

    private var additionalPaddingWidth: Int = 0

    private val callDepthMultiplier: Int
        get() = CALL_DEPTH_INDENT_MULTIPLIER

    private val spinCycleMinWidth: Int
        get() = SPIN_CYCLE_INDENT_MIN_WIDTH

    fun appendTraceNode(node: TraceNode?) {
        if (node == null) {
            _lines.add(TraceLine.EMPTY)
            return
        }
        updateSpinCycleState(node)
//        TODO: Iterations with ranges do not show up. In those cases the node is CallNode.
        if ((node is LoopNode || node is CallNode) && !verbose) {
            val loopLine = node.toString(withLocation = false)
            var loopTitle = loopLine.substringBefore("::")
            var iterations = loopLine.substringAfter("::").split(";").map { it.trim() }.dropLast(1) // drop last empty element after split

            var prefixIterations = getPrefix() + " "

            if (iterations.any { it.contains("switch (reason: active lock detected)") }) {
                val switchIndex = iterations.indexOfFirst { it.contains("switch (reason: active lock detected)") }

                loopTitle = "┌╶> $loopTitle"

                iterations = iterations.mapIndexed { index, iteration ->
                    val arrow = when {
                        index < switchIndex -> "|    "
                        index == switchIndex -> "└╶╶ "
                        else -> "     "
                    }
                    arrow + iteration
                }
                prefixIterations = prefixIterations.dropLast(1)
            }
            val loopTitleLine = TraceLine(node.eventNumber, node.iThread, getPrefix() + loopTitle)
            _lines.add(loopTitleLine)

            val traceLines = iterations.filter { !it.isEmpty() }

            // Each traceLine contains the operation and the event number. Here we split them and keep only unique operations
            val seenTraceLines = mutableSetOf<String>()
            val eventNumbers = mutableSetOf<Int>()
            traceLines.forEach { iteration ->
                val iterationWithoutEventNumber = iteration.substringBeforeLast("(").trimEnd()
                val eventNumber = iteration.substringAfterLast("(").substringBefore(")").toInt()
                if (iterationWithoutEventNumber.contains("switch")) {
                    seenTraceLines.add(iterationWithoutEventNumber)
                    eventNumbers.add(eventNumber)
                }
                if (iterationWithoutEventNumber !in seenTraceLines) {
                    seenTraceLines.add(iterationWithoutEventNumber)
                    eventNumbers.add(eventNumber)
                }
            }

            seenTraceLines.zip(eventNumbers).map { (iteration, eventNumber) ->
                TraceLine(eventNumber, node.iThread, prefixIterations + iteration)
            }.forEach { traceLine ->
                _lines.add(traceLine)
            }
        }

        else {
            val nodeLine = getPrefix() + node.toString(withLocation = verbose)

            val traceLine = TraceLine(node.eventNumber, node.iThread, nodeLine)
            _lines.add(traceLine)
        }

        val isUnfoldableNode = node is CallNode || node is LoopNode || node is IterationNode || node is RecursionNode
        if (isUnfoldableNode && (filter?.shouldUnfold(node) ?: true)) {
            pushCallStack(node)
            try {
                val children = filter?.filterChildren(node) ?: node.children
                for (child in children) {
                    appendTraceNode(child)
                }
            } finally {
                popCallStack()
            }
        }
    }

    private fun pushCallStack(node: TraceNode) {
        callStack.add(node)
    }

    private fun popCallStack() {
        callStack.removeLast()
    }

    private fun getPrefix(): String {
        val paddingWidth = callDepth * callDepthMultiplier + additionalPaddingWidth

        val spinCycleState = spinCycleState // redeclare local val for smart casting
        if (spinCycleState != null && spinCycleState != SpinCycleState.HEADER) {
            check(spinCycleDepth >= 0)
            val spinIndentWidth = ((callDepth - spinCycleDepth).coerceAtLeast(0) * callDepthMultiplier)
            val spinIndent = spinCycleState.indent.repeat(spinIndentWidth)
            val spacePaddingWidth = paddingWidth - (spinCycleState.prefix.length + 1) - spinIndent.length
            val spacePadding = " ".repeat(spacePaddingWidth.coerceAtLeast(0))

            // depending on spin state, returns one of these (assuming 1 call depth pad on each side):
            // - "  ┌╶>   "
            // - "  |     "
            // - "  └╶╶╶╶ "
            return spacePadding + spinCycleState.prefix + spinIndent + " "
        }

        return " ".repeat(paddingWidth)
    }

    fun updateAdditionalPaddingWidth(node: TraceNode) {
        check(node.parent == null) {
            "Additional padding width should be calculated only for root nodes"
        }

        val spinCycleLookupDepth = spinCycleMinWidth / callDepthMultiplier
        val spinStartLevel = node.findLevelOf(spinCycleLookupDepth) {
            it.tracePoint.isSpinCycleStartTracePoint
        }
        if (spinStartLevel >= 0) {
            additionalPaddingWidth = max(
                additionalPaddingWidth,
                (SPIN_CYCLE_INDENT_MIN_WIDTH - (spinStartLevel * callDepthMultiplier))
            )
        }
    }

    private fun updateSpinCycleState(node: TraceNode) {
        when {
            node is EventNode &&
            node.tracePoint.isSpinCycleStartTracePoint -> {
                check(spinCycleState == null || spinCycleState == SpinCycleState.END)
                spinCycleState = SpinCycleState.HEADER
                spinCycleDepth = callDepth
            }
            spinCycleState == SpinCycleState.HEADER -> {
                spinCycleState = SpinCycleState.START
            }
            spinCycleState == SpinCycleState.START && !node.tracePoint.isSpinCycleEndTracePoint -> {
                spinCycleState = SpinCycleState.INSIDE
            }
            node is EventNode &&
            node.tracePoint.isSpinCycleEndTracePoint &&
            (spinCycleState == SpinCycleState.START || spinCycleState == SpinCycleState.INSIDE) -> {
                spinCycleState = SpinCycleState.END
            }
            spinCycleState == SpinCycleState.END -> {
                spinCycleState = null
                spinCycleDepth = -1
            }
        }
    }

    private enum class SpinCycleState { HEADER, START, INSIDE, END }

    private val SpinCycleState.prefix: String get() = when (this) {
        SpinCycleState.START  -> "┌╶>"
        SpinCycleState.INSIDE -> "|  "
        SpinCycleState.END    -> "└╶╶"
        else                  -> ""
    }

    private val SpinCycleState.indent: String get() = when (this) {
        SpinCycleState.START  -> " "
        SpinCycleState.INSIDE -> " "
        SpinCycleState.END    -> "╶"
        else                  -> ""
    }
}

private const val CALL_DEPTH_INDENT_MULTIPLIER : Int = 2  // indent on each call depth level
private const val SPIN_CYCLE_INDENT_MIN_WIDTH  : Int = 4  // min. indent of a trace point related to spin cycle

private val TracePoint.isSpinCycleStartTracePoint: Boolean get() =
    this is SpinCycleStartTracePoint

private val TracePoint.isSpinCycleEndTracePoint: Boolean get() =
    this is ObstructionFreedomViolationExecutionAbortTracePoint ||
    this is SwitchEventTracePoint

internal fun traceToCollapsedTree(trace: Trace, analysisProfile: AnalysisProfile): MultiThreadedTable<TraceNode> {
    // Turn trace into a tree which is List of sections, where a section is a list of root nodes (actors).
    var traceTree = traceToTree(trace.threadNames.size, trace)
    traceTree = foldEquivalentLoopIterations(traceTree)
    traceTree = foldRecursiveCalls(traceTree)
    return traceTree
        .map { it
            .compressTrace()
            .collapseLibraries(analysisProfile)
        }
        .map { it.removeEmptyHungActors() }
}
