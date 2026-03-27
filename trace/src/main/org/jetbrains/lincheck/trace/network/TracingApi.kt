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

import org.jetbrains.lincheck.trace.NetworkTraceReader
import java.io.Closeable

/**
 * Interface for receiving notifications from the tracing server.
 * This is implemented by the client (controller).
 */
interface TracingCallbacks : Closeable {
    fun hitLimitReached(breakpointData: LiveDebuggerNotification.BreakpointData, timestamp: Long)
    fun conditionUnsafe(breakpointData: LiveDebuggerNotification.BreakpointData, timestamp: Long)
    fun binaryTraceData(data: ByteArray)
    
    companion object {
        internal const val HIT_LIMIT_REACHED = "hitLimitReached"
        internal const val CONDITION_UNSAFE = "conditionUnsafe"
    }
}

/**
 * Interface for sending commands to the tracing server.
 * This is implemented by the server (agent) or a client-side proxy.
 */
interface TracingCommands {
    fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean)
    fun startNetworkTracing()
    fun stopTracing()
    fun addBreakpoints(breakpoints: List<String>)
    fun removeBreakpoints(breakpoints: List<String>)
    
    companion object {
        internal const val START_FILE_TRACING = "startFileTracing"
        internal const val START_NETWORK_TRACING = "startNetworkTracing"
        internal const val STOP_TRACING = "stopTracing"
        internal const val ADD_BREAKPOINTS = "addBreakpoints"
        internal const val REMOVE_BREAKPOINTS = "removeBreakpoints"
    }
}


/**
 * Interface for components that need to be notified when a connection is opened.
 */
interface ConnectedAware {
    fun onConnectionReady() {}
    fun onDisconnected() {}
}

/**
 * A tracing client that can send commands to the server, receive notifications, and read binary trace data.
 */
interface TracingClient: TracingCallbacks, ConnectedAware {
    val connection: TracingCommands
    val networkTraceReader: NetworkTraceReader
}

/**
 * A tracing server that accepts commands and sends notifications to the connected client.
 */
interface TracingServer: TracingCommands, ConnectedAware, Closeable {
    val connection: TracingCallbacks
}