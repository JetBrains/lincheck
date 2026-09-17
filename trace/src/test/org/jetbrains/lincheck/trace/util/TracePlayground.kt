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

import org.jetbrains.lincheck.trace.serialization.LazyTraceReader
import org.jetbrains.lincheck.trace.printing.printTraceTree

fun main(args: Array<String>) {
    // Choose one!

    // lazyLoadAndPrintTrace("<path to binary file>")
}

private fun lazyLoadAndPrintTrace(fileName: String) {
    LazyTraceReader(fileName).use { reader ->
        printTraceTree(System.out, reader, verbose = true)
    }
}
