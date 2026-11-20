/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.recorder.network.websocket

import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.jetbrains.lincheck.trace.serialization.NetworkTraceReader
import org.jetbrains.lincheck.trace.recorder.network.TracingClient
import org.jetbrains.lincheck.trace.recorder.network.TracingCallbacks
import org.jetbrains.lincheck.trace.recorder.network.TracingServer
import org.jetbrains.lincheck.trace.recorder.network.TracingCommands
import org.jetbrains.lincheck.util.Logger
import java.lang.Exception
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer

/**
 * Parses and dispatches an incoming WebSocket command message to the appropriate [TracingCommands] method.
 */
fun TracingCommands.handleMessage(message: String?) {
    if (message == null) return
    try {
        val parts = message.split(":", limit = 2)
        val command = parts[0]
        when (command) {
            TracingCommands.START_FILE_TRACING -> {
                if (parts.size < 2) return
                val args = parts[1].split(":")
                if (args.size >= 2) {
                    startFileTracing(args[0], args[1].toBoolean())
                }
            }
            TracingCommands.START_NETWORK_TRACING -> startNetworkTracing()

            TracingCommands.STOP_TRACING -> stopTracing()

            else -> Logger.warn { "Unknown command received: $command" }
        }
    } catch (e: Exception) {
        Logger.error(e) { "Error handling WebSocket command: $message" }
    }
}

/**
 * Base class for WebSocket clients that implement [TracingCallbacks].
 * Processes binary trace frames received from the recorder.
 */
abstract class TracingWebSocketClient(serverUri: URI) : TracingClient {
    private val webSocketConnection: WebSocketClient = object : WebSocketClient(serverUri) {
        override fun onOpen(handshakedata: ServerHandshake?) = onConnectionReady()

        override fun onMessage(message: String?) {}

        override fun onMessage(bytes: ByteBuffer?) {
            if (bytes == null) return
            val data = ByteArray(bytes.remaining())
            bytes.get(data)
            networkTraceReader.processMessage(data)
            binaryTraceData(data)
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            networkTraceReader.handleDisconnect()
            onDisconnected()
        }

        override fun onError(ex: Exception?) {
            if (ex != null) Logger.warn(ex) { "WebSocket client error" }
            else Logger.warn { "WebSocket client error" }
        }
    }

    override val connection: TracingCommands = WebSocketTracingCommandSender(webSocketConnection)
    override val networkTraceReader: NetworkTraceReader = NetworkTraceReader()

    init {
        webSocketConnection.connect()
    }

    override fun binaryTraceData(data: ByteArray) {}

    override fun close() = webSocketConnection.close()
}

/**
 * Base WebSocket server that receives commands from clients and delegates them to [TracingCommands].
 */
abstract class TracingWebSocketServer(address: InetSocketAddress) : TracingServer {
    private var _client: TracingCallbacks = ClientSink()

    override val connectedClient: TracingCallbacks?
        get() = synchronized(this) { _client.takeUnless { it is ClientSink } }

    private val webSocketServer = object : WebSocketServer(address) {
        override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
            if (conn != null) {
                synchronized(this@TracingWebSocketServer) {
                    _client.close()
                    _client = WebSocketTracingNotifier(conn)
                    onConnectionReady()
                }
            }
        }

        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
            synchronized(this@TracingWebSocketServer) {
                val client = _client
                if (client is WebSocketTracingNotifier && client.webSocket == conn) {
                    onDisconnected()
                    _client = ClientSink()
                }
            }
        }

        override fun onMessage(conn: WebSocket?, message: String?) = handleMessage(message)

        override fun onError(conn: WebSocket?, ex: Exception?) {
            if (ex != null) Logger.error(ex) { "WebSocket server error" }
            else Logger.error { "WebSocket server error" }
        }

        override fun onStart() {}
    }

    init {
        try {
            webSocketServer.isDaemon = true
            webSocketServer.isReuseAddr = true
            webSocketServer.start()
        } catch (e: Exception) {
            Logger.error(e) { "Failed to start WebSocket server" }
        }
    }

    override fun close() {
        try {
            webSocketServer.stop()
            _client.close()
        } catch (e: Exception) {
            Logger.error(e) { "Failed to stop WebSocket server" }
        }
    }

    override val connection: TracingCallbacks
        get() = synchronized(this) { _client }

}
