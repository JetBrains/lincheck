/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.strategy.tracerecorder.TraceRecorder
import org.junit.Test

class TraceRecorderInternalApiTest {
    var a = 0

    @Test
    fun recorderTest() = TraceRecorder.recordTraceInternal("recorded.txt") {
        a++
        hello()
    }

    private fun hello() {
        a++
    }
}