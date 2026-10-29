/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.network

import org.jetbrains.lincheck.settings.BreakpointExpressionSlot
import org.jetbrains.lincheck.trace.network.websocket.handleMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** Decodes `breakpointExpressionEvaluationFailed` frames in the layout both the JVM and the Python agents send. */
class ExpressionEvaluationFailedWireProtocolTest {

    private data class Failure(
        val breakpointData: LiveDebuggerNotification.BreakpointData,
        val slot: BreakpointExpressionSlot,
        val failureKind: String,
        val message: String,
        val disabled: Boolean,
        val timestamp: Long,
    )

    private class RecordingCallbacks : TracingCallbacks {
        var failure: Failure? = null
        override fun hitLimitReached(breakpointData: LiveDebuggerNotification.BreakpointData, timestamp: Long) {}
        override fun breakpointExpressionUnsafe(
            breakpointData: LiveDebuggerNotification.BreakpointData,
            slot: BreakpointExpressionSlot,
            safetyViolationMessage: String,
            timestamp: Long,
        ) {}
        override fun breakpointBlocked(
            breakpointData: LiveDebuggerNotification.BreakpointData,
            reason: String,
            timestamp: Long,
        ) {}
        override fun breakpointExpressionEvaluationFailed(
            breakpointData: LiveDebuggerNotification.BreakpointData,
            slot: BreakpointExpressionSlot,
            failureKind: String,
            message: String,
            disabled: Boolean,
            timestamp: Long,
        ) {
            failure = Failure(breakpointData, slot, failureKind, message, disabled, timestamp)
        }
        override fun breakpointHitSuppressed(
            breakpointData: LiveDebuggerNotification.BreakpointData,
            blockedFrameClass: String,
            reason: String,
            timestamp: Long,
        ) {}
        override fun binaryTraceData(data: ByteArray) {}
        override fun close() {}
    }

    private val data = LiveDebuggerNotification.BreakpointData(
        breakpointUuid = UUID.randomUUID(),
        className = "com.corp.Orders",
        fileName = "Orders.java",
        lineNumber = 42,
    )

    @Test
    fun `JVM frame with an empty message decodes`() {
        val recorder = RecordingCallbacks()
        recorder.handleMessage(
            "${TracingCallbacks.BREAKPOINT_EXPRESSION_EVALUATION_FAILED}:12345:Condition;$data;false;java.lang.NullPointerException;"
        )

        assertEquals(
            Failure(data, BreakpointExpressionSlot.Condition, "java.lang.NullPointerException", "", false, 12345),
            recorder.failure,
        )
    }

    @Test
    fun `disabled frame keeps separators in the trailing message`() {
        val message = "`d[k]` could not be evaluated: KeyError: 'a;b:c'"
        val recorder = RecordingCallbacks()
        recorder.handleMessage("${TracingCallbacks.BREAKPOINT_EXPRESSION_EVALUATION_FAILED}:7:Watch;$data;true;KeyError;$message")

        assertEquals(Failure(data, BreakpointExpressionSlot.Watch, "KeyError", message, true, 7), recorder.failure)
    }

    @Test
    fun `frame with an unknown disabled flag is dropped`() {
        val recorder = RecordingCallbacks()
        recorder.handleMessage("${TracingCallbacks.BREAKPOINT_EXPRESSION_EVALUATION_FAILED}:7:Condition;$data;maybe;KeyError;")

        assertNull(recorder.failure)
    }

    @Test
    fun `frame with missing fields is dropped`() {
        val recorder = RecordingCallbacks()
        recorder.handleMessage("${TracingCallbacks.BREAKPOINT_EXPRESSION_EVALUATION_FAILED}:7:Condition;$data;false")

        assertNull(recorder.failure)
    }
}
