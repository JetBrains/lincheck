/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.descriptors.LineCodeLocation
import org.jetbrains.lincheck.settings.BreakpointExpressionSlot
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.trace.network.LiveDebuggerNotification
import org.jetbrains.lincheck.trace.network.TracingCallbacks
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.UUID

class NetworkRuntimeTest {
    @Test
    fun senderPreservesForeignRuntimeAndDefaultsToJvm() {
        val context = TraceContext()
        context.setThreadName(0, "main")
        val location = context.codeLocationsPool.register(
            LineCodeLocation(StackTraceElement("example.Service", "run", "Service.kt", 1), emptyList())
        )
        val point = SnapshotLineBreakpointTracePoint(
            context = context, codeLocationId = location, threadId = 0,
            breakpointUuid = UUID.randomUUID(), stackTraceCodeLocationIds = emptyList(),
            currentTimeMillis = 1L, locals = emptyList(), watches = emptyList(), traceId = null, eventId = 0,
        )
        for (runtime in listOf(null, "foreign-runtime")) {
            val frames = mutableListOf<ByteArray>()
            val callbacks = object : TracingCallbacks {
                override fun binaryTraceData(data: ByteArray) { frames += data }
                override fun close() {}
                override fun hitLimitReached(breakpointData: LiveDebuggerNotification.BreakpointData, timestamp: Long) {}
                override fun breakpointExpressionUnsafe(
                    breakpointData: LiveDebuggerNotification.BreakpointData, slot: BreakpointExpressionSlot,
                    safetyViolationMessage: String, timestamp: Long,
                ) {}
                override fun breakpointBlocked(
                    breakpointData: LiveDebuggerNotification.BreakpointData, reason: String, timestamp: Long,
                ) {}
                override fun breakpointHitSuppressed(
                    breakpointData: LiveDebuggerNotification.BreakpointData, blockedFrameClass: String,
                    reason: String, timestamp: Long,
                ) {}
            }
            val sender = if (runtime == null) NetworkTracePointSender(callbacks, context)
                         else NetworkTracePointSender(callbacks, context, runtime)
            sender.send(point)
            sender.send(point)
            assertEquals(3, frames.size)
            DataInputStream(ByteArrayInputStream(frames.first())).use { input ->
                assertEquals(TRACE_MAGIC, input.readLong())
                assertEquals(TRACE_VERSION, input.readLong())
                assertEquals(runtime ?: RUNTIME_JVM, input.readUTF())
                assertEquals(0, input.available())
            }
        }
    }
}
