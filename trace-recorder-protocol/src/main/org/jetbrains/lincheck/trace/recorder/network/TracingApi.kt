/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.recorder.network

import org.jetbrains.lincheck.trace.network.TraceDataSink
import org.jetbrains.lincheck.trace.network.TraceTransport
import org.jetbrains.lincheck.trace.serialization.NetworkTraceReader

/**
 * The trace recorder only accepts tracing session commands.
 */
interface TracingCommands {
    fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean)
    fun startNetworkTracing()
    fun stopTracing()

    companion object {
        internal const val START_FILE_TRACING = "startFileTracing"
        internal const val START_NETWORK_TRACING = "startNetworkTracing"
        internal const val STOP_TRACING = "stopTracing"
    }
}

interface TracingCallbacks : TraceDataSink

interface ConnectedAware {
    fun onConnectionReady() {}
    fun onDisconnected() {}
}

interface TracingClient : TracingCallbacks, ConnectedAware {
    val connection: TracingCommands
    val networkTraceReader: NetworkTraceReader
}

interface TracingServer : TracingCommands, ConnectedAware, TraceTransport {
    val connection: TracingCallbacks
    override val connectedClient: TracingCallbacks?
}
