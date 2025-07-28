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

import java.io.OutputStream
import java.io.PrintStream

private const val OUTPUT_BUFFER_SIZE: Int = 16*1024*1024

fun printRecorderTrace(fileName: String?, context: TraceContext, rootCallsPerThread: List<TRTracePoint>, verbose: Boolean) =
    printRecorderTrace(
        output = if (fileName == null) System.out else openNewFile(fileName),
        context = context,
        rootCallsPerThread = rootCallsPerThread,
        verbose = verbose
    )

fun printRecorderTrace(output: OutputStream, context: TraceContext, rootCallsPerThread: List<TRTracePoint>, verbose: Boolean) {
    check(context == TRACE_CONTEXT) { "Now only global TRACE_CONTEXT is supported" }
    PrintStream(output.buffered(OUTPUT_BUFFER_SIZE)).use { output ->
        val appendable = DefaultTRTextAppendable(output)
        rootCallsPerThread.forEachIndexed { i, root ->
            output.println("# Thread ${i+1}")
            printTRPoint(appendable, root, 0, verbose)
        }
    }
}

private fun printTRPoint(appendable: TRAppendable, node: TRTracePoint, depth: Int, verbose: Boolean) {
    appendable.append(" ".repeat(depth * 2))
    node.toText(appendable, verbose)
    appendable.append("\n")
    if (node is TRMethodCallTracePoint) {
        var unloaded = 0
        node.events.forEach { event ->
            if (event == null) {
                unloaded++
            } else {
                reportUnloaded(appendable, unloaded, depth + 1)
                unloaded = 0
                printTRPoint(appendable, event, depth + 1, verbose)
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