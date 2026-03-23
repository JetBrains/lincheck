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
import org.jetbrains.lincheck.trace.network.LiveDebuggerNotification
import org.jetbrains.lincheck.trace.network.TracingClientApi
import org.jetbrains.lincheck.trace.network.TracingServerApi
import java.io.Closeable

/**
 * Client-side implementation of [TracingServerApi] that sends commands to the server via WebSocket.
 */
class WebSocketTracingController(private val webSocketConnection: WebSocketClient) : Closeable, TracingServerApi {
    override fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean) {
        webSocketConnection.send("${TracingServerApi.START_FILE_TRACING}:$traceDumpFilePath:$packTrace")
    }

    override fun startNetworkTracing() {
        webSocketConnection.send(TracingServerApi.START_NETWORK_TRACING)
    }

    override fun stopTracing() {
        webSocketConnection.send(TracingServerApi.STOP_TRACING)
    }

    override fun addBreakpoints(breakpoints: List<String>) {
        webSocketConnection.send("${TracingServerApi.ADD_BREAKPOINTS}:${breakpoints.joinToString(",")}")
    }

    override fun removeBreakpoints(breakpoints: List<String>) {
        webSocketConnection.send("${TracingServerApi.REMOVE_BREAKPOINTS}:${breakpoints.joinToString(",")}")
    }

    override fun close() = webSocketConnection.close()
}

/**
 * WebSocket implementation of [ClientConnection] on the server side.
 */
class WebSocketTracingNotifier(val webSocket: WebSocket) : TracingClientApi {
    override fun hitLimitReached(breakpointData: LiveDebuggerNotification.BreakpointData, timestamp: Long) {
        webSocket.send("${TracingClientApi.HIT_LIMIT_REACHED}:$timestamp:$breakpointData")
    }

    override fun conditionUnsafe(breakpointData: LiveDebuggerNotification.BreakpointData, timestamp: Long) {
        webSocket.send("${TracingClientApi.CONDITION_UNSAFE}:$timestamp:$breakpointData")
    }

    override fun binaryTraceData(data: ByteArray) {
        if (webSocket.isOpen) {
            webSocket.send(data)
        }
    }

    override fun close() = webSocket.close()
}

class ClientSink: Closeable, TracingClientApi {
    override fun hitLimitReached(breakpointData: LiveDebuggerNotification.BreakpointData, timestamp: Long) {}
    override fun conditionUnsafe(breakpointData: LiveDebuggerNotification.BreakpointData, timestamp: Long) {}
    override fun binaryTraceData(data: ByteArray) {}
    override fun close() {}
}
