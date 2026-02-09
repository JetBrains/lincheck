/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.jmx

import org.jetbrains.lincheck.trace.TcpTraceReader

interface TracingJmxController {

    /**
     * Starts tracing writing the trace output to the specified file.
     *
     * @param traceDumpFilePath the file path where the trace output will be saved.
     * @param packTrace whether to compress the trace output into a zip file.
     */
    fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean)

    /**
     * Starts TCP trace streaming.
     *
     * The trace producer acts as a server,
     * listening for incoming reader connections on an automatically assigned port.
     * Clients should connect to this port using [TcpTraceReader] class.
     */
    fun startTcpTracing()

    /**
     * Stops the current tracing operation.
     */
    fun stopTracing()

    /**
     * Adds breakpoints to Live Debugger.
     */
    fun addBreakpoints(breakpoints: List<String>)

    /**
     * Removes breakpoints from Live Debugger.
     */
    fun removeBreakpoints(breakpoints: List<String>)
}