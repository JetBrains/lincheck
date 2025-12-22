/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import java.io.File
import java.io.OutputStream
import java.io.PrintStream

fun printPostProcessedTrace(outputFileName: String?, context: TraceContext, rootCallsPerThread: List<TRTracePoint>, verbose: Boolean) {
    val input = File.createTempFile("lincheck-trace", ".tmp")
    saveRecorderTrace(input.absolutePath, context, rootCallsPerThread)
    printPostProcessedTrace(outputFileName, input.absolutePath, verbose)
    input.delete()
}

fun printPostProcessedTrace(outputFileName: String?, inputFileName: String, verbose: Boolean) {
    val reader = LazyTraceReader(inputFileName)
    printPostProcessedTrace(outputFileName, reader, verbose)
}

fun printPostProcessedTrace(outputFileName: String?, reader: LazyTraceReader, verbose: Boolean) {
    val output = if (outputFileName == null) System.out else openNewFile(outputFileName)
    printPostProcessedTrace(output, reader, verbose)
}

fun printPostProcessedTrace(outputStream: OutputStream, reader: LazyTraceReader, verbose: Boolean) {
    val roots = reader.readRoots()

    PrintStream(outputStream.buffered(OUTPUT_BUFFER_SIZE)).use { output ->
        roots.forEachIndexed { i, root ->
            output.println(getThreadName(i, roots.size, reader.context))
            lazyPrintTRPoint(output, reader, root, 0, verbose)
        }
    }
}

private fun lazyPrintTRPoint(output: PrintStream, reader: LazyTraceReader, node: TRTracePoint, depth: Int, verbose: Boolean) {
    output.print(" ".repeat(depth * 2))
    output.println(node.toText(verbose))
    if (node is TRContainerTracePoint && node.events.isNotEmpty()) {
        reader.loadAllChildren(node)
        node.events.forEach { event ->
            if (event != null) {
                lazyPrintTRPoint(output, reader, event, depth + 1, verbose)
            }
        }
        node.unloadAllChildren()
    }
}

fun printRecorderTrace(fileName: String?, context: TraceContext, rootCallsPerThread: List<TRTracePoint>, verbose: Boolean) =
    printRecorderTrace(
        output = if (fileName == null) System.out else openNewFile(fileName),
        context = context,
        rootCallsPerThread = rootCallsPerThread,
        verbose = verbose
    )

fun printRecorderTrace(output: OutputStream, context: TraceContext, rootCallsPerThread: List<TRTracePoint>, verbose: Boolean) {
    PrintStream(output.buffered(OUTPUT_BUFFER_SIZE)).use { output ->
        val appendable = DefaultTRTextAppendable(output, verbose)
        rootCallsPerThread.forEachIndexed { i, root ->
            output.println(getThreadName(i, rootCallsPerThread.size, context))
            printTRPoint(appendable, root, 0)
        }
    }
}

private fun printTRPoint(appendable: TRAppendable, node: TRTracePoint, depth: Int) {
    appendable.append(" ".repeat(depth * 2))
    node.toText(appendable)
    appendable.append("\n")
    if (node is TRContainerTracePoint) {
        var unloaded = 0
        node.events.forEach { event ->
            if (event == null) {
                unloaded++
            } else {
                reportUnloaded(appendable, unloaded, depth + 1)
                unloaded = 0
                printTRPoint(appendable, event, depth + 1)
            }
        }
        reportUnloaded(appendable, unloaded, depth + 1)
    }
}

private fun reportUnloaded(appendable: TRAppendable, unloaded: Int, depth: Int) {
    if (unloaded == 1) {
        appendable.append(" ".repeat(depth * 2))
        appendable.append("... <unloaded child>\n")
    } else if (unloaded > 1) {
        appendable.append(" ".repeat(depth * 2))
        appendable.append("... <${unloaded} unloaded children>\n")
    }
}

private fun getThreadName(idx: Int, totalThreads: Int, context: TraceContext): String {
    val name = "# Thread ${idx + 1}"
    if (totalThreads == 1) return name
    return name + " (${context.getThreadName(idx)})"
}