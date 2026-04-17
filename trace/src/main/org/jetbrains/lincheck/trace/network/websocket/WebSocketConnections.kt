/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.network.websocket

import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.jetbrains.lincheck.trace.network.LiveDebuggerNotification
import org.jetbrains.lincheck.trace.network.TracingCallbacks
import org.jetbrains.lincheck.trace.network.TracingCommands
import org.jetbrains.lincheck.util.Logger
import java.io.Closeable

/**
 * Client-side implementation of [TracingCommands] that sends commands to the server via WebSocket.
 */
class WebSocketTracingController(private val webSocketConnection: WebSocketClient) : Closeable, TracingCommands {
    override fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean) {
        webSocketConnection.send("${TracingCommands.START_FILE_TRACING}:$traceDumpFilePath:$packTrace")
    }

    override fun startNetworkTracing() {
        webSocketConnection.send(TracingCommands.START_NETWORK_TRACING)
    }

    override fun stopTracing() {
        webSocketConnection.send(TracingCommands.STOP_TRACING)
    }

    override fun addBreakpoints(breakpoints: List<String>) {
        webSocketConnection.send("${TracingCommands.ADD_BREAKPOINTS}:${breakpoints.joinToString(",")}")
    }

    override fun removeBreakpoints(breakpoints: List<String>) {
        webSocketConnection.send("${TracingCommands.REMOVE_BREAKPOINTS}:${breakpoints.joinToString(",")}")
    }

    override fun close() = webSocketConnection.close()
}

/**
 * Server-side [TracingCallbacks] that sends notifications and binary trace data to the connected client over WebSocket.
 */
class WebSocketTracingNotifier(val webSocket: WebSocket) : TracingCallbacks {

    override fun hitLimitReached(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        timestamp: Long
    ) {
        webSocket.send("${TracingCallbacks.HIT_LIMIT_REACHED}:$timestamp:$breakpointData")
    }

    override fun conditionUnsafe(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        safetyViolationMessage: String,
        timestamp: Long
    ) {
        webSocket.send("${TracingCallbacks.CONDITION_UNSAFE}:$timestamp:$breakpointData;$safetyViolationMessage")
    }

    override fun binaryTraceData(data: ByteArray) {
        if (webSocket.isOpen) {
            webSocket.send(data)
        }
    }

    override fun close() = webSocket.close()
}

/**
 * No-op [TracingCallbacks] used as a placeholder when no client is connected.
 */
class ClientSink: Closeable, TracingCallbacks {
    override fun hitLimitReached(breakpointData: LiveDebuggerNotification.BreakpointData, timestamp: Long) {
        Logger.warn { "hitLimitReached dropped: no client connected (breakpoint=$breakpointData)" }
    }
    override fun conditionUnsafe(breakpointData: LiveDebuggerNotification.BreakpointData, safetyViolationMessage: String, timestamp: Long) {
        Logger.warn { "conditionUnsafe dropped: no client connected (breakpoint=$breakpointData, violation=$safetyViolationMessage)" }
    }
    override fun binaryTraceData(data: ByteArray) {
        Logger.warn { "binaryTraceData dropped: no client connected (${data.size} bytes)" }
    }
    override fun close() {}
}
