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

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.jetbrains.lincheck.settings.BreakpointExpressionSlot
import org.jetbrains.lincheck.trace.network.websocket.TracingWebSocketClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The upgrade-request contract of [TracingWebSocketClient.handshakeHeaders]:
 * the legacy constructor sends no `Authorization` header, and the authenticated
 * constructor sends exactly the given bearer value.
 */
class WebSocketHandshakeHeadersTest {

    private class HandshakeCapture(val hasAuthorization: Boolean, val authorization: String?)

    private val handshakes = LinkedBlockingQueue<HandshakeCapture>()
    private val started = CountDownLatch(1)

    private val server = object : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
        override fun onStart() = started.countDown()
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            handshakes.add(HandshakeCapture(handshake.hasFieldValue("Authorization"), handshake.getFieldValue("Authorization")))
        }
        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {}
        override fun onMessage(conn: WebSocket?, message: String?) {}
        override fun onError(conn: WebSocket?, ex: Exception?) {}
    }.apply { start() }

    private val clients = mutableListOf<TracingWebSocketClient>()

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        server.stop(1000)
    }

    private fun connect(headers: Map<String, String>): HandshakeCapture {
        assertTrue("server did not start", started.await(10, TimeUnit.SECONDS))
        val uri = URI("ws://127.0.0.1:${server.port}")
        clients += object : TracingWebSocketClient(uri, null, headers) {
            override fun hello(hello: AgentHelloMessage) {}
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
            override fun onDisconnected() {}
        }
        return checkNotNull(handshakes.poll(10, TimeUnit.SECONDS)) { "no handshake arrived" }
    }

    @Test
    fun legacyConnectionSendsNoAuthorizationHeader() {
        val handshake = connect(emptyMap())
        assertFalse(handshake.hasAuthorization)
    }

    @Test
    fun authenticatedConnectionSendsTheExactBearerHeader() {
        val handshake = connect(mapOf("Authorization" to "Bearer test-access-token"))
        assertTrue(handshake.hasAuthorization)
        assertEquals("Bearer test-access-token", handshake.authorization)
    }
}
