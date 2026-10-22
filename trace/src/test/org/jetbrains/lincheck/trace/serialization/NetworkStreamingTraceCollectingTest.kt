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

import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.trace.SnapshotLineBreakpointTracePoint
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.UNKNOWN_CODE_LOCATION_ID
import org.jetbrains.lincheck.trace.network.TracingCallbacks
import org.jetbrains.lincheck.trace.network.TracingServer
import org.jetbrains.lincheck.trace.network.websocket.ClientSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The network writer holds recorded hits while no client is connected and streams them once one is,
 * so a session that starts before its client (a launch tracing from the first line of `main`) keeps
 * its early hits. When the trace ends with no client, the hits go to the undelivered-hits file
 * instead of holding the process for a late client. A hit whose send fails because the client left
 * mid-write is kept the same way: the next client gets it, or the file does.
 */
class NetworkStreamingTraceCollectingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun hitsRecordedBeforeAClientConnectsAreStreamedOnceItDoes() {
        val server = FakeServer()
        val context = TraceContext()
        val strategy = NetworkStreamingTraceCollecting(context, server)
        try {
            strategy.tracePointCreated(null, snapshot(context))
            strategy.tracePointCreated(null, snapshot(context))
            Thread.sleep(300)
            assertTrue("nothing must be written without a client", server.frames.data.isEmpty())

            server.connectedClient = server.frames
            awaitFrames(server, expected = 3) // header + two points
        } finally {
            strategy.traceEnded()
        }
        assertEquals(3, server.frames.data.size)
    }

    @Test
    fun traceEndWithoutAClientWritesTheHitsToTheFileAtOnce() {
        val server = FakeServer()
        val context = TraceContext()
        val file = undeliveredHitsFile()
        val strategy = NetworkStreamingTraceCollecting(context, server, undeliveredHitsFilePath = file.path)
        val first = snapshot(context)
        val second = snapshot(context)
        strategy.tracePointCreated(null, first)
        strategy.tracePointCreated(null, second)

        val started = System.currentTimeMillis()
        strategy.traceEnded()

        assertTrue("traceEnded must not wait for a client", System.currentTimeMillis() - started < 4_000)
        assertTrue(server.frames.data.isEmpty())
        assertEquals(
            listOf(first.breakpointUuid, second.breakpointUuid),
            readUndeliveredHitsFile(file).map { it.breakpointUuid },
        )
    }

    @Test
    fun hitsTheClientReceivedAreNotWrittenToTheFile() {
        val server = FakeServer().apply { connectedClient = frames }
        val context = TraceContext()
        val file = undeliveredHitsFile()
        val strategy = NetworkStreamingTraceCollecting(context, server, undeliveredHitsFilePath = file.path)
        strategy.tracePointCreated(null, snapshot(context))
        awaitFrames(server, expected = 2) // header + point

        strategy.traceEnded()

        assertEquals(2, server.frames.data.size)
        assertFalse("the file is only written when something was left undelivered", file.exists())
    }

    @Test
    fun traceEndWithoutAClientOrAFileDropsTheHits() {
        val server = FakeServer()
        val context = TraceContext()
        val strategy = NetworkStreamingTraceCollecting(context, server)
        strategy.tracePointCreated(null, snapshot(context))

        val started = System.currentTimeMillis()
        strategy.traceEnded()

        assertTrue(System.currentTimeMillis() - started < 4_000)
        assertTrue(server.frames.data.isEmpty())
    }

    @Test
    fun aHitWhoseSendFailedGoesToTheFile() {
        val lostClient = LostClient()
        val server = FakeServer().apply { connectedClient = lostClient }
        val context = TraceContext()
        val file = undeliveredHitsFile()
        val strategy = NetworkStreamingTraceCollecting(context, server, undeliveredHitsFilePath = file.path)
        val hit = snapshot(context)
        strategy.tracePointCreated(null, hit)
        await("the writer must have tried the send") { lostClient.attempts.get() > 0 }
        server.connectedClient = null // the server noticed the client is gone

        strategy.traceEnded()

        assertEquals(listOf(hit.breakpointUuid), readUndeliveredHitsFile(file).map { it.breakpointUuid })
    }

    @Test
    fun aHitWhoseSendFailedGoesToTheNextClient() {
        val lostClient = LostClient()
        val server = FakeServer().apply { connectedClient = lostClient }
        val context = TraceContext()
        val file = undeliveredHitsFile()
        val strategy = NetworkStreamingTraceCollecting(context, server, undeliveredHitsFilePath = file.path)
        strategy.tracePointCreated(null, snapshot(context))
        await("the writer must have tried the send") { lostClient.attempts.get() > 0 }

        server.connectedClient = server.frames // a new client connects
        awaitFrames(server, expected = 2) // header + the kept hit
        strategy.traceEnded()

        assertEquals(2, server.frames.data.size)
        assertFalse("the hit reached the new client, so nothing is left for the file", file.exists())
    }

    private fun undeliveredHitsFile(): File = File(temporaryFolder.root, "undelivered-hits.bin")

    private fun awaitFrames(server: FakeServer, expected: Int) =
        await("expected $expected frames") { server.frames.data.size >= expected }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(what, condition())
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

    /** A client whose socket is gone: every frame handed to it fails, as a closed WebSocket does. */
    private class LostClient : TracingCallbacks by FrameCapture() {
        val attempts = AtomicInteger()

        override fun binaryTraceData(data: ByteArray) {
            attempts.incrementAndGet()
            throw IllegalStateException("socket closed")
        }
    }

    /** A server whose client is whatever [connectedClient] holds; [frames] is a client that keeps what it is sent. */
    private class FakeServer : TracingServer {
        val frames = FrameCapture()
        @Volatile override var connectedClient: TracingCallbacks? = null

        override val connection: TracingCallbacks get() = connectedClient ?: ClientSink()

        override fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean) = Unit
        override fun startNetworkTracing() = Unit
        override fun stopTracing() = Unit
        override fun addBreakpoints(breakpoints: List<SnapshotBreakpoint>) = Unit
        override fun removeBreakpoints(uuids: List<UUID>) = Unit
        override fun addSensitiveAreaBlocklists(blocklists: List<SensitiveAreaBlocklist>) = Unit
        override fun close() = Unit
    }
}
