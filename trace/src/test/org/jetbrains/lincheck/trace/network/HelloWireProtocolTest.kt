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
import org.jetbrains.lincheck.trace.serialization.TRACE_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trips the agent `hello` handshake through its payload encoding and the WebSocket wire dispatch. */
class HelloWireProtocolTest {

    private class RecordingCallbacks : TracingCallbacks {
        var hello: AgentHelloMessage? = null
        override fun hello(hello: AgentHelloMessage) {
            this.hello = hello
        }
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
        override fun breakpointHitSuppressed(
            breakpointData: LiveDebuggerNotification.BreakpointData,
            blockedFrameClass: String,
            reason: String,
            timestamp: Long,
        ) {}
        override fun binaryTraceData(data: ByteArray) {}
        override fun close() {}
    }

    @Test
    fun `payload round-trips, including extra attributes`() {
        val hello = AgentHelloMessage(
            protocolVersion = PROTOCOL_VERSION,
            runtime = RUNTIME_JVM,
            runtimeVersion = "17.0.11",
            agentVersion = "1.2.3",
            timestamp = 1_754_000_000_000L,
            attributes = mapOf("os" to "linux", "arch" to "amd64"),
        )
        assertEquals(hello, AgentHelloMessage.decodeFromPayload(hello.encodeToPayload(), hello.timestamp))
    }

    @Test
    fun `encoding is deterministic with well-known keys first and attributes sorted`() {
        val hello = AgentHelloMessage(
            protocolVersion = 1,
            runtime = "python",
            runtimeVersion = "3.12",
            agentVersion = "0.1",
            timestamp = 42L,
            attributes = mapOf("zeta" to "z", "alpha" to "a"),
        )
        // The timestamp rides in the frame header like every other notification, so it is not in the payload.
        assertEquals(
            "protocol=1;runtime=python;runtimeVersion=3.12;agentVersion=0.1;alpha=a;zeta=z",
            hello.encodeToPayload(),
        )
        assertFalse(hello.encodeToPayload().contains("42"))
    }

    @Test
    fun `hello round-trips through the wire dispatch, taking its timestamp from the frame`() {
        val hello = AgentHelloMessage(
            protocolVersion = PROTOCOL_VERSION,
            runtime = RUNTIME_JVM,
            runtimeVersion = "21.0.2",
            agentVersion = "dev",
            timestamp = 12345L,
        )
        val message = "${TracingCallbacks.HELLO}:${hello.timestamp}:${hello.encodeToPayload()}"

        val recorder = RecordingCallbacks()
        recorder.handleMessage(message)

        assertEquals(hello, recorder.hello)
        assertEquals(12345L, recorder.hello?.timestamp)
    }

    @Test
    fun `unknown attributes from a newer agent are preserved for forward compatibility`() {
        // A future agent appends a capability key this build has no typed field for.
        val payload = "protocol=2;runtime=jvm;runtimeVersion=21;agentVersion=9.9;capabilities=redaction,downgrade"

        val recorder = RecordingCallbacks()
        recorder.handleMessage("${TracingCallbacks.HELLO}:7:$payload")

        val received = recorder.hello
        assertTrue(received != null)
        assertEquals(2, received!!.protocolVersion)
        assertEquals("redaction,downgrade", received.attributes["capabilities"])
    }

    @Test
    fun `attribute values may contain the frame separator`() {
        // The frame splits on ':' with limit=3, so a ':' inside the payload's last field survives.
        val hello = AgentHelloMessage(
            protocolVersion = 1,
            runtime = RUNTIME_JVM,
            runtimeVersion = "17.0.1",
            agentVersion = "1.0",
            timestamp = 99L,
            attributes = mapOf("build" to "2026-08-06T12:00:00"),
        )
        val message = "${TracingCallbacks.HELLO}:${hello.timestamp}:${hello.encodeToPayload()}"

        val recorder = RecordingCallbacks()
        recorder.handleMessage(message)

        assertEquals("2026-08-06T12:00:00", recorder.hello?.attributes?.get("build"))
    }

    @Test
    fun `a payload missing a required key does not dispatch a hello`() {
        assertNull(AgentHelloMessage.decodeFromPayload("runtime=jvm;runtimeVersion=17;agentVersion=1.0", 5L))

        val recorder = RecordingCallbacks()
        recorder.handleMessage("${TracingCallbacks.HELLO}:5:runtime=jvm;runtimeVersion=17;agentVersion=1.0")
        assertNull(recorder.hello)
    }

    @Test
    fun `trace version round-trips through payload and wire dispatch`() {
        val hello = AgentHelloMessage(
            protocolVersion = PROTOCOL_VERSION,
            runtime = RUNTIME_JVM,
            runtimeVersion = "21.0.2",
            agentVersion = "dev",
            timestamp = 1L,
            traceVersion = TRACE_VERSION,
        )
        assertEquals(hello, AgentHelloMessage.decodeFromPayload(hello.encodeToPayload(), hello.timestamp))

        val recorder = RecordingCallbacks()
        recorder.handleMessage("${TracingCallbacks.HELLO}:${hello.timestamp}:${hello.encodeToPayload()}")
        assertEquals(TRACE_VERSION, recorder.hello?.traceVersion)
    }

    @Test
    fun `an absent trace version decodes to null`() {
        // A runtime that does not stream this trace format simply omits the key.
        val decoded =
            AgentHelloMessage.decodeFromPayload("protocol=1;runtime=python;runtimeVersion=3.12;agentVersion=0.1", 1L)
        assertNull(decoded?.traceVersion)
    }
}
