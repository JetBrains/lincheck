/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.livedebugger.network.websocket

import org.java_websocket.WebSocket
import org.jetbrains.lincheck.settings.BreakpointExpressionSlot
import org.jetbrains.lincheck.settings.privacy.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.settings.encodeToString
import org.jetbrains.lincheck.settings.privacy.encodeToString
import org.jetbrains.lincheck.livedebugger.network.AgentHelloMessage
import org.jetbrains.lincheck.livedebugger.network.LiveDebuggerNotification
import org.jetbrains.lincheck.livedebugger.network.LiveDebuggerCallbacks
import org.jetbrains.lincheck.livedebugger.network.LiveDebuggerCommands
import org.jetbrains.lincheck.util.Logger
import java.io.Closeable
import java.util.UUID

/**
 * Implementation of [LiveDebuggerCommands] that sends commands over a raw [WebSocket] connection.
 */
class WebSocketLiveDebuggerCommandSender(private val webSocket: WebSocket) : Closeable, LiveDebuggerCommands {
    override fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean) {
        webSocket.send("${LiveDebuggerCommands.START_FILE_TRACING}:$traceDumpFilePath:$packTrace")
    }

    override fun startNetworkTracing() {
        webSocket.send(LiveDebuggerCommands.START_NETWORK_TRACING)
    }

    override fun stopTracing() {
        webSocket.send(LiveDebuggerCommands.STOP_TRACING)
    }

    override fun addBreakpoints(breakpoints: List<SnapshotBreakpoint>) {
        webSocket.send("${LiveDebuggerCommands.ADD_BREAKPOINTS}:${breakpoints.encodeToString()}")
    }

    override fun removeBreakpoints(uuids: List<UUID>) {
        webSocket.send("${LiveDebuggerCommands.REMOVE_BREAKPOINTS}:${uuids.joinToString(",")}")
    }

    override fun addSensitiveAreaBlocklists(blocklists: List<SensitiveAreaBlocklist>) {
        // The blocklists payload may itself contain ':' (rule fields),
        // so the receiver splits off the command with limit=2.
        webSocket.send("${LiveDebuggerCommands.ADD_SENSITIVE_AREA_BLOCKLISTS}:${blocklists.encodeToString()}")
    }

    override fun close() = webSocket.close()
}

/**
 * Server-side [LiveDebuggerCallbacks] that sends notifications and binary trace data to the connected client over WebSocket.
 */
class WebSocketLiveDebuggerNotifier(val webSocket: WebSocket) : LiveDebuggerCallbacks {

    override fun hello(hello: AgentHelloMessage) {
        webSocket.send("${LiveDebuggerCallbacks.HELLO}:${hello.timestamp}:${hello.encodeToPayload()}")
    }

    override fun hitLimitReached(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        timestamp: Long
    ) {
        webSocket.send("${LiveDebuggerCallbacks.HIT_LIMIT_REACHED}:$timestamp:$breakpointData")
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
        webSocket.send("${LiveDebuggerCallbacks.BREAKPOINT_EXPRESSION_UNSAFE}:$timestamp:$payload")
    }

    override fun breakpointBlocked(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        reason: String,
        timestamp: Long
    ) {
        // Layout: breakpointData ; reason. breakpointData has no ';'; reason absorbs the rest.
        val payload = "$breakpointData;$reason"
        webSocket.send("${LiveDebuggerCallbacks.BREAKPOINT_BLOCKED}:$timestamp:$payload")
    }

    override fun breakpointExpressionEvaluationFailed(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        slot: BreakpointExpressionSlot,
        failureKind: String,
        message: String,
        disabled: Boolean,
        timestamp: Long
    ) {
        // Layout: slot ; breakpointData ; disabled ; failureKind ; message.
        // Only the trailing message may contain ';'.
        val payload = "${slot.name};$breakpointData;$disabled;${failureKind.replace(';', '_')};$message"
        webSocket.send("${LiveDebuggerCallbacks.BREAKPOINT_EXPRESSION_EVALUATION_FAILED}:$timestamp:$payload")
    }

    override fun breakpointExpressionCompilationFailed(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        compilationFailureMessage: String,
        timestamp: Long
    ) {
        val payload = "$breakpointData;$compilationFailureMessage"
        webSocket.send("${LiveDebuggerCallbacks.BREAKPOINT_EXPRESSION_COMPILATION_FAILED}:$timestamp:$payload")
    }

    override fun invalidBreakpointLocation(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        reason: String,
        timestamp: Long
    ) {
        val payload = "$breakpointData;$reason"
        webSocket.send("${LiveDebuggerCallbacks.INVALID_BREAKPOINT_LOCATION}:$timestamp:$payload")
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
        webSocket.send("${LiveDebuggerCallbacks.BREAKPOINT_HIT_SUPPRESSED}:$timestamp:$payload")
    }

    // No isOpen guard: a closed socket must raise (Java-WebSocket does) rather than swallow the frame,
    // so the trace writer keeps the hit for the next client or the undelivered-hits file.
    override fun binaryTraceData(data: ByteArray) = webSocket.send(data)

    override fun close() = webSocket.close()
}

/**
 * Placeholder [LiveDebuggerCallbacks] while no client is connected: everything handed to it is dropped with a warning.
 */
class ClientSink: Closeable, LiveDebuggerCallbacks {
    override fun hello(hello: AgentHelloMessage) {
        Logger.warn { "hello dropped: no client connected ($hello)" }
    }
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
    override fun breakpointExpressionEvaluationFailed(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        slot: BreakpointExpressionSlot,
        failureKind: String,
        message: String,
        disabled: Boolean,
        timestamp: Long,
    ) {
        Logger.warn { "breakpointExpressionEvaluationFailed dropped: no client connected (breakpoint=$breakpointData, kind=$slot, failure=$failureKind, disabled=$disabled)" }
    }
    override fun breakpointExpressionCompilationFailed(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        compilationFailureMessage: String,
        timestamp: Long,
    ) {
        Logger.warn { "breakpointExpressionCompilationFailed dropped: no client connected (breakpoint=$breakpointData, message=$compilationFailureMessage)" }
    }
    override fun invalidBreakpointLocation(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        reason: String,
        timestamp: Long,
    ) {
        Logger.warn { "invalidBreakpointLocation dropped: no client connected (breakpoint=$breakpointData, message=$reason)" }
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
