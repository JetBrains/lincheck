/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.trace

import org.jetbrains.kotlinx.lincheck.trace.MethodCallTracePoint
import org.jetbrains.kotlinx.lincheck.trace.Trace
import org.jetbrains.kotlinx.lincheck.trace.removeGPMCLambda
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.UNKNOWN_CODE_LOCATION_ID
import org.junit.Assert.assertEquals
import org.junit.Test

class GPMCLambdaTraceTest {
    @Test
    fun `unfinished GPMC lambda has no return to remove`() {
        val context = TraceContext()
        val gpmcCall = MethodCallTracePoint(
            context = context,
            eventId = 0,
            iThread = 0,
            actorId = -1,
            className = "java.lang.Thread",
            methodName = "run",
            codeLocation = UNKNOWN_CODE_LOCATION_ID,
            isStatic = false,
            callType = MethodCallTracePoint.CallType.THREAD_RUN,
            isSuspend = false,
        )
        val nestedCall = MethodCallTracePoint(
            context = context,
            eventId = 1,
            iThread = 0,
            actorId = -1,
            className = "Test",
            methodName = "work",
            codeLocation = UNKNOWN_CODE_LOCATION_ID,
            isStatic = false,
            isSuspend = false,
        )

        val processed = Trace(listOf(gpmcCall, nestedCall), listOf("Main Thread")).removeGPMCLambda()

        assertEquals(listOf(nestedCall), processed.trace)
    }
}
