/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_trace.util

import org.jetbrains.lincheck.trace.*
import org.junit.Test

fun main(args: Array<String>) {
    // Choose one!

    // eagerLoadAndPrintTrace("<path to binary file>")

    // lazyLoadAndPrintTrace("<path to binary file>")

    printPostProcessedTrace(null, "/Users/dmitriiart/IdeaProjects/lincheck/trace/output", verbose = true)
}

class TRPlayground {
    var escape: Any? = null

    @Test
    fun testing() {
        escape = "START"
        for (i in 1..3) {
            val a: Any = i
            escape = a.toString()
        }
        escape = "END"
    }
}


private fun eagerLoadAndPrintTrace(fileName: String) {
    val trace = loadRecordedTrace(fileName)
    printRecorderTrace(System.out, trace.context, trace.roots, true)
}

private fun lazyLoadAndPrintTrace(fileName: String) {
    val reader = LazyTraceReader(fileName)
    val trace = reader.readRoots()
    reader.loadChildrenRange(trace.first() as TRMethodCallTracePoint, 5, 5)
    // Or you could run or other variant to load children
    // reader.loadAllChildren(trace.first() as TRMethodCallTracePoint)
    printRecorderTrace(System.out, reader.context, trace, true)
}
