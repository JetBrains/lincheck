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

import org.jetbrains.kotlinx.lincheck.tracedata.loadRecordedTrace
import org.jetbrains.kotlinx.lincheck.tracedata.printRecorderTrace
import java.io.FileInputStream

fun main(args: Array<String>) {
    val trace = loadRecordedTrace(FileInputStream("output.bin"))
    printRecorderTrace(System.out, trace, true)
}