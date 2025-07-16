/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.util

import org.jetbrains.lincheck.trace.LazyTraceReader
import org.jetbrains.lincheck.trace.TRMethodCallTracePoint
import org.jetbrains.lincheck.trace.loadRecordedTrace
import org.jetbrains.lincheck.trace.printRecorderTrace
import java.io.FileInputStream

fun main(args: Array<String>) {
    // Choose one!

    // eagerLoadAndPrintTrace("<path to binary file>")

    lazyLoadAndPrintTrace("C:\\home\\lev\\dada\\tests.stress.set.PersistentHashSetTest_containsTests12483937858569643790.bin")
}

private fun eagerLoadAndPrintTrace(fileName: String) {
    val trace = loadRecordedTrace(FileInputStream(fileName))
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
