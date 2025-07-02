/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.util

import org.jetbrains.kotlinx.lincheck.tracedata.LazyTraceReader
import org.jetbrains.kotlinx.lincheck.tracedata.TRACE_CONTEXT
import org.jetbrains.kotlinx.lincheck.tracedata.TRMethodCallTracePoint
import org.jetbrains.kotlinx.lincheck.tracedata.loadRecordedTrace
import org.jetbrains.kotlinx.lincheck.tracedata.printRecorderTrace
import java.io.FileInputStream

fun main(args: Array<String>) {
    // val (context, trace) = loadRecordedTrace(FileInputStream("C:\\home\\lev\\Projects\\kotlinx.collections.immutable\\core\\output.bin"))

    val list = listOf(0, 1024, 2048, 1024*3, 1024*4, 1024*5, 1024*6)
    val idx = list.binarySearch(1025)
    System.out.println(idx)

/*
    val reader = LazyTraceReader("C:\\home\\lev\\Projects\\kotlinx.collections.immutable\\core\\output.bin",
        TRACE_CONTEXT
    )
    val trace = reader.readRoots()
    reader.readChildren(trace.first() as TRMethodCallTracePoint)
    printRecorderTrace(System.out, TRACE_CONTEXT, trace, true)
*/
}