/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.controller

import org.jetbrains.lincheck.trace.NetworkTraceReader

/**
 * Controller interface for managing tracing.
 */
interface TracingController {

    /**
     * Starts tracing writing the trace output to the specified file.
     *
     * @param traceDumpFilePath the file path where the trace output will be saved.
     * @param packTrace whether to compress the trace output into a zip file.
     */
    fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean)

    /**
     * Starts WebSocket trace streaming.
     *
     * The trace producer acts as a server,
     * listening for incoming reader connections on an automatically assigned port.
     * Clients connect to this port using [NetworkTraceReader] returned as a result.
     *
     * @return trace reader instance for reading the trace data,
     *   `null` if tracing was not started due to some internal error.
     */
    fun startNetworkTracing(): NetworkTraceReader?

    /**
     * Stops the current tracing operation.
     */
    fun stopTracing()

    companion object {
        const val DEFAULT_TRACING_HOST = "localhost"
        const val DEFAULT_TRACING_PORT = 9997
    }
}