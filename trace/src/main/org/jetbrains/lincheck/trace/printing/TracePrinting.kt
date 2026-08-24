/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.printing

import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.trace.serialization.*
import org.jetbrains.lincheck.trace.tree.compressedView
import org.jetbrains.lincheck.trace.tree.readTraceTrees
import org.jetbrains.lincheck.util.tree.Tree
import org.jetbrains.lincheck.util.tree.unloadChildren
import java.io.File
import java.io.OutputStream
import java.io.PrintStream

/**
 * Saves [trees] into a temporary trace file and prints it back
 * with the tree-based printer (see the reader-based [printTraceTree] overload).
 */
fun printTraceTree(outputFileName: String?, context: TraceContext, trees: List<Tree<TRTracePoint>>, verbose: Boolean) {
    val input = File.createTempFile("lincheck-trace", ".tmp")
    saveRecorderTrace(input.absolutePath, context, trees)
    LazyTraceReader(input.absolutePath).use { reader ->
        val output = if (outputFileName == null) System.out else openNewFile(outputFileName)
        printTraceTree(output, reader, verbose)
    }
    input.delete()
}

/**
 * Prints the trace read by [reader] by traversing it through the
 * [org.jetbrains.lincheck.trace.tree.LazyLoadableTraceTree] API instead of manipulating [TRTracePoint] children directly.
 *
 * The `CompressingPostprocessor` modifications are applied as rewrite rules over the tree (see [compressedView]).
 * The tree path reads trace points shallowly and never invokes the reader's postprocessor.
 */
fun printTraceTree(outputStream: OutputStream, reader: LazyTraceReader, verbose: Boolean) {
    val trees = reader.readTraceTrees().map { it.compressedView(reader.context) }

    PrintStream(outputStream.buffered(OUTPUT_BUFFER_SIZE)).use { output ->
        trees.forEach { tree ->
            val root = tree.root ?: return@forEach
            output.println(getThreadName(root.data.threadId, trees.size, reader.context))
            printTraceTreeNode(output, root, 0, verbose)
        }
    }
}

private fun printTraceTreeNode(output: PrintStream, node: Tree.Node<TRTracePoint>, depth: Int, verbose: Boolean) {
    output.print(" ".repeat(depth * 2))
    output.println(node.data.toText(verbose, node.parent?.data))
    node.children.forEach { child ->
        printTraceTreeNode(output, child, depth + 1, verbose)
    }
    // Keep only the current path materialized, like the non-tree printing above.
    node.unloadChildren()
}

private fun getThreadName(idx: Int, totalThreads: Int, context: TraceContext): String {
    val name = "# Thread ${idx + 1}"
    if (totalThreads == 1) return name
    return name + " (${context.getThreadName(idx)})"
}