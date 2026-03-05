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

import org.jetbrains.lincheck.trace.NetworkTraceReader
import javax.management.NotificationEmitter

/**
 * JMX MBean interface for managing tracing operations.
 */
interface TracingJmxMBean : NotificationEmitter {

    /**
     * The name of the MBean.
     */
    val name: String

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
     * Clients should connect to this port using [NetworkTraceReader] class.
     */
    fun startNetworkTracing()

    /**
     * Stops the current tracing operation.
     */
    fun stopTracing()
}