/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.network.ws

import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.jetbrains.lincheck.trace.NetworkTraceReader
import org.jetbrains.lincheck.trace.network.LiveDebuggerNotification
import org.jetbrains.lincheck.trace.network.TracingClient
import org.jetbrains.lincheck.trace.network.TracingClientApi
import org.jetbrains.lincheck.trace.network.TracingServer
import org.jetbrains.lincheck.trace.network.TracingServerApi
import org.jetbrains.lincheck.util.Logger
import java.lang.Exception
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer

/**
 * Parses and dispatches an incoming WebSocket command message to the appropriate [TracingServerApi] method.
 */
fun TracingServerApi.handleMessage(message: String?) {
    if (message == null) return
    try {
        val parts = message.split(":", limit = 2)
        val command = parts[0]
        when (command) {
            TracingServerApi.START_FILE_TRACING -> {
                if (parts.size < 2) return
                val args = parts[1].split(":")
                if (args.size >= 2) {
                    startFileTracing(args[0], args[1].toBoolean())
                }
            }

            TracingServerApi.START_NETWORK_TRACING -> startNetworkTracing()
            TracingServerApi.STOP_TRACING -> stopTracing()
            TracingServerApi.ADD_BREAKPOINTS -> {
                val breakpoints = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].split(",") else emptyList()
                addBreakpoints(breakpoints)
            }

            TracingServerApi.REMOVE_BREAKPOINTS -> {
                val breakpoints = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].split(",") else emptyList()
                removeBreakpoints(breakpoints)
            }

            else -> Logger.warn { "Unknown command received: $command" }
        }
    } catch (e: Exception) {
        Logger.error(e) { "Error handling WebSocket command: $message" }
    }
}

/**
 * Parses and dispatches an incoming WebSocket notification message to the appropriate [TracingClientApi] method.
 */
fun TracingClientApi.handleMessage(message: String?) {
    if (message == null) return
    try {
        val parts = message.split(":", limit = 3)
        if (parts.size < 3) return

        val type = parts[0]
        val timestamp = parts[1].toLongOrNull() ?: return
        val data = parts[2]

        when (type) {
            TracingClientApi.HIT_LIMIT_REACHED -> {
                val breakpointData = LiveDebuggerNotification.BreakpointData.parseFromString(data)
                if (breakpointData == null) {
                    Logger.warn { "Failed to parse breakpoint data from hitLimitReached notification: $data" }
                    return
                }
                hitLimitReached(breakpointData, timestamp)
            }
            TracingClientApi.CONDITION_UNSAFE -> {
                val breakpointData = LiveDebuggerNotification.BreakpointData.parseFromString(data)
                if (breakpointData == null) {
                    Logger.warn { "Failed to parse breakpoint data from conditionUnsafe notification: $data" }
                    return
                }
                conditionUnsafe(breakpointData, timestamp)
            }
            else -> Logger.warn { "Unknown notification received: $type" }
        }
    } catch (e: Exception) {
        Logger.error(e) { "Error handling WebSocket notification: $message" }
    }
}

/**
 * Base class for WebSocket clients that implement [TracingClientApi].
 * It handles incoming WebSocket messages and dispatches them to the corresponding API methods.
 */
abstract class TracingWebSocketClient(serverUri: URI) : TracingClient {
    private val webSocketConnection: WebSocketClient = object : WebSocketClient(serverUri) {
        override fun onOpen(handshakedata: ServerHandshake?) = onConnectionReady()

        override fun onMessage(message: String?) = this@TracingWebSocketClient.handleMessage(message)

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
    
    override val connection: TracingServerApi = WebSocketTracingController(webSocketConnection)
    override val networkTraceReader: NetworkTraceReader = NetworkTraceReader()
    
    init {
        webSocketConnection.connect()
    }

    override fun binaryTraceData(data: ByteArray) {}

    override fun close() = webSocketConnection.close()
}

/**
 * Base WebSocket server that receives commands from clients and delegates them to [TracingServerApi].
 */
abstract class TracingWebSocketServer(address: InetSocketAddress) : TracingServer {
    private val webSocketServer = object: WebSocketServer(address) {
        override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
            if (conn != null) {
                synchronized(this@TracingWebSocketServer) {
                    _client.close()
                    onDisconnected()
                    _client = WebSocketTracingNotifier(conn)
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
        } catch (e: Exception) {
            Logger.error(e) { "Failed to stop WebSocket server" }
        }
    }
    
    private var _client: TracingClientApi = ClientSink()

    override val connection: TracingClientApi
        get() = synchronized(this) { _client }

}