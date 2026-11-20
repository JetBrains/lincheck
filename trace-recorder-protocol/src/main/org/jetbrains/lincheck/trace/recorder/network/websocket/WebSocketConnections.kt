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
import org.jetbrains.lincheck.trace.recorder.network.TracingCallbacks
import org.jetbrains.lincheck.trace.recorder.network.TracingCommands
import org.jetbrains.lincheck.util.Logger
import java.io.Closeable

/**
 * Implementation of [TracingCommands] that sends commands over a raw [WebSocket] connection.
 */
class WebSocketTracingCommandSender(private val webSocket: WebSocket) : Closeable, TracingCommands {
    override fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean) {
        webSocket.send("${TracingCommands.START_FILE_TRACING}:$traceDumpFilePath:$packTrace")
    }

    override fun startNetworkTracing() {
        webSocket.send(TracingCommands.START_NETWORK_TRACING)
    }

    override fun stopTracing() {
        webSocket.send(TracingCommands.STOP_TRACING)
    }

    override fun close() = webSocket.close()
}

/**
 * Server-side [TracingCallbacks] that sends notifications and binary trace data to the connected client over WebSocket.
 */
class WebSocketTracingNotifier(val webSocket: WebSocket) : TracingCallbacks {

    // No isOpen guard: a closed socket must raise (Java-WebSocket does) rather than swallow the frame,
    // so the trace writer keeps the hit for the next client or the undelivered-hits file.
    override fun binaryTraceData(data: ByteArray) = webSocket.send(data)

    override fun close() = webSocket.close()
}

/**
 * Placeholder [TracingCallbacks] while no client is connected: everything handed to it is dropped with a warning.
 */
class ClientSink: Closeable, TracingCallbacks {
    override fun binaryTraceData(data: ByteArray) {
        Logger.warn { "binaryTraceData dropped: no client connected (${data.size} bytes)" }
    }
    override fun close() {}
}
