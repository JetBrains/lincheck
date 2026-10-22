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

import org.jetbrains.lincheck.settings.BreakpointExpressionSlot
import org.jetbrains.lincheck.trace.SnapshotLineBreakpointTracePoint
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.UNKNOWN_CODE_LOCATION_ID
import org.jetbrains.lincheck.trace.network.LiveDebuggerNotification
import org.jetbrains.lincheck.trace.network.TracingCallbacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * A reader starts paused; frames that arrive before its consumer calls [NetworkTraceReader.start]
 * must be kept and delivered in order, not dropped — a launched JVM streams its first hits before
 * the IDE has opened the session that subscribes.
 */
class NetworkTraceReaderBufferingTest {

    @Test
    fun tracePointsReceivedBeforeStartAreDeliveredOnStart() {
        val context = TraceContext()
        val frames = FrameCapture()
        val sender = NetworkTracePointSender(frames, context)
        val first = snapshot(context)
        val second = snapshot(context)
        sender.send(first)
        sender.send(second)

        val reader = NetworkTraceReader()
        val delivered = mutableListOf<UUID>()
        reader.addTracePointListener { delivered += it.breakpointUuid }
        frames.data.forEach(reader::processMessage)
        assertTrue("paused reader must not deliver yet", delivered.isEmpty())
        assertEquals(2, reader.getTotalTracePointCount())

        reader.start()
        assertEquals(listOf(first.breakpointUuid, second.breakpointUuid), delivered)

        val third = snapshot(context)
        sender.send(third)
        reader.processMessage(frames.data.last())
        assertEquals(listOf(first.breakpointUuid, second.breakpointUuid, third.breakpointUuid), delivered)
    }

    @Test
    fun tracePointsReceivedBeforeStartSurviveTheSourceDisconnecting() {
        val context = TraceContext()
        val frames = FrameCapture()
        val sender = NetworkTracePointSender(frames, context)
        val point = snapshot(context)
        sender.send(point)

        val reader = NetworkTraceReader()
        val delivered = mutableListOf<UUID>()
        reader.addTracePointListener { delivered += it.breakpointUuid }
        frames.data.forEach(reader::processMessage)
        // A short-lived process streams its hits and exits before the consumer subscribes.
        reader.handleDisconnect()
        assertTrue(delivered.isEmpty())

        reader.start()

        assertEquals(listOf(point.breakpointUuid), delivered)
        assertTrue(reader.isFinished())
    }

    @Test
    fun stoppedReaderIgnoresTheRestOfTheStream() {
        val context = TraceContext()
        val frames = FrameCapture()
        val sender = NetworkTracePointSender(frames, context)
        sender.send(snapshot(context))

        val reader = NetworkTraceReader()
        val delivered = mutableListOf<UUID>()
        reader.addTracePointListener { delivered += it.breakpointUuid }
        reader.processMessage(frames.data[0]) // header
        reader.stop()
        reader.processMessage(frames.data[1])
        reader.start()

        assertTrue(delivered.isEmpty())
        assertEquals(0, reader.getTotalTracePointCount())
    }

    private fun snapshot(context: TraceContext) = SnapshotLineBreakpointTracePoint(
        context = context,
        codeLocationId = UNKNOWN_CODE_LOCATION_ID,
        threadId = 0,
        breakpointUuid = UUID.randomUUID(),
        stackTraceCodeLocationIds = emptyList(),
        currentTimeMillis = System.currentTimeMillis(),
        locals = emptyList(),
        watches = emptyList(),
        traceId = null,
    )
}

/** Collects the binary frames a sender emits, in order. */
internal class FrameCapture : TracingCallbacks {
    val data = mutableListOf<ByteArray>()

    override fun hitLimitReached(breakpointData: LiveDebuggerNotification.BreakpointData, timestamp: Long) = Unit

    override fun breakpointExpressionUnsafe(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        slot: BreakpointExpressionSlot,
        safetyViolationMessage: String,
        timestamp: Long,
    ) = Unit

    override fun breakpointBlocked(breakpointData: LiveDebuggerNotification.BreakpointData, reason: String, timestamp: Long) = Unit

    override fun breakpointHitSuppressed(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        blockedFrameClass: String,
        reason: String,
        timestamp: Long,
    ) = Unit

    override fun binaryTraceData(data: ByteArray) {
        synchronized(this.data) { this.data += data }
    }

    override fun close() = Unit
}
