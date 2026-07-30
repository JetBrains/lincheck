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
import org.jetbrains.lincheck.settings.BreakpointExpressionSlot
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.settings.encodeToString
import org.jetbrains.lincheck.trace.network.LiveDebuggerNotification
import org.jetbrains.lincheck.trace.network.TracingCallbacks
import org.jetbrains.lincheck.trace.network.TracingCommands
import org.jetbrains.lincheck.util.Logger
import java.io.Closeable
import java.util.UUID

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

    override fun addBreakpoints(breakpoints: List<SnapshotBreakpoint>) {
        webSocket.send("${TracingCommands.ADD_BREAKPOINTS}:${breakpoints.encodeToString()}")
    }

    override fun removeBreakpoints(uuids: List<UUID>) {
        webSocket.send("${TracingCommands.REMOVE_BREAKPOINTS}:${uuids.joinToString(",")}")
    }

    override fun addSensitiveAreaBlocklists(blocklists: List<SensitiveAreaBlocklist>) {
        // The blocklists payload may itself contain ':' (rule fields),
        // so the receiver splits off the command with limit=2.
        webSocket.send("${TracingCommands.ADD_SENSITIVE_AREA_BLOCKLISTS}:${blocklists.encodeToString()}")
    }

    override fun close() = webSocket.close()
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

    override fun breakpointExpressionUnsafe(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        slot: BreakpointExpressionSlot,
        safetyViolationMessage: String,
        timestamp: Long
    ) {
        // Layout: kind ; breakpointData ; safetyViolationMessage.
        // breakpointData has no `;`; the trailing message absorbs everything after the 2nd `;`.
        val payload = "${slot.name};$breakpointData;$safetyViolationMessage"
        webSocket.send("${TracingCallbacks.BREAKPOINT_EXPRESSION_UNSAFE}:$timestamp:$payload")
    }

    override fun breakpointBlocked(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        reason: String,
        timestamp: Long
    ) {
        // Layout: breakpointData ; reason. breakpointData has no ';'; reason absorbs the rest.
        val payload = "$breakpointData;$reason"
        webSocket.send("${TracingCallbacks.BREAKPOINT_BLOCKED}:$timestamp:$payload")
    }

    override fun breakpointHitSuppressed(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        blockedFrameClass: String,
        reason: String,
        timestamp: Long
    ) {
        // Layout: breakpointData ; blockedFrameClass ; reason. The first two have no ';';
        // reason absorbs the rest.
        val payload = "$breakpointData;$blockedFrameClass;$reason"
        webSocket.send("${TracingCallbacks.BREAKPOINT_HIT_SUPPRESSED}:$timestamp:$payload")
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
    override fun breakpointExpressionUnsafe(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        slot: BreakpointExpressionSlot,
        safetyViolationMessage: String,
        timestamp: Long,
    ) {
        Logger.warn { "breakpointExpressionUnsafe dropped: no client connected (breakpoint=$breakpointData, kind=$slot, violation=$safetyViolationMessage)" }
    }
    override fun breakpointBlocked(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        reason: String,
        timestamp: Long,
    ) {
        Logger.warn { "breakpointBlocked dropped: no client connected (breakpoint=$breakpointData, reason=$reason)" }
    }
    override fun breakpointHitSuppressed(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        blockedFrameClass: String,
        reason: String,
        timestamp: Long,
    ) {
        Logger.warn { "breakpointHitSuppressed dropped: no client connected (breakpoint=$breakpointData, frame=$blockedFrameClass, reason=$reason)" }
    }
    override fun binaryTraceData(data: ByteArray) {
        Logger.warn { "binaryTraceData dropped: no client connected (${data.size} bytes)" }
    }
    override fun close() {}
}
