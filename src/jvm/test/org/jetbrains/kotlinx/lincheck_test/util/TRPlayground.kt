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
import org.jetbrains.lincheck.trace.TRACE_CONTEXT
import org.jetbrains.lincheck.trace.TRMethodCallTracePoint
import org.jetbrains.lincheck.trace.printRecorderTrace

fun main(args: Array<String>) {
    // val (context, trace) = loadRecordedTrace(FileInputStream("C:\\home\\lev\\Projects\\kotlinx.collections.immutable\\core\\output.bin"))

    val reader = LazyTraceReader("C:\\home\\lev\\Projects\\kotlinx.collections.immutable\\core\\output.bin",
        TRACE_CONTEXT
    )
    val trace = reader.readRoots()
    reader.readChildren(trace.first() as TRMethodCallTracePoint)
    printRecorderTrace(System.out, TRACE_CONTEXT, trace, true)
}