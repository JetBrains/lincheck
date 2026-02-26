/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.tracer

/**
 * Trace output mode defines trace collecting and output strategy,
 * for instance, whether to keep the trace in-memory or streaming incrementally,
 * whether to save it to file or transfer over the network, etc.
 */
sealed class TraceOutputMode {
    /**
     * Write a binary format directly to the output file, without collecting it in memory.
     *
     * @param streamingFilePath if specified, writes the trace to the specified file in binary format.
     */
    class BinaryFileStream(val streamingFilePath: String? = null) : TraceOutputMode()

    /**
     * Collect full trace in the memory and dump to the output file at the end of the run.
     */
    class BinaryFileDump : TraceOutputMode()

    /**
     * Collect the full trace in memory and print it as text to the output file.
     *
     * @param verbose if true, prints code locations along each trace line.
     */
    class Text(val verbose: Boolean = false) : TraceOutputMode()

    /**
     * Stream binary trace data over WebSocket to connected readers.
     *
     * The trace producer acts as a server,
     * listening for incoming reader connections on an assigned port.
     * Multiple readers can connect and receive the trace data simultaneously.
     */
    class BinaryTcpStream(val onDisconnect: () -> Unit) : TraceOutputMode()

    /**
     * Throws away all recorded trace data.
     * Used primarily for testing and benchmarking purposes.
     */
    object Null : TraceOutputMode()

    companion object {
        fun parse(outputMode: String?, outputOption: String?, outputFilePath: String?): TraceOutputMode {
            return when {
                outputMode.equals("binary", ignoreCase = true) -> {
                    if (outputOption.equals("dump", ignoreCase = true)) {
                        BinaryFileDump()
                    } else {
                        BinaryFileStream(outputFilePath)
                    }
                }

                outputMode.equals("text", ignoreCase = true) -> {
                    Text(verbose = outputOption.equals("verbose", ignoreCase = true))
                }

                outputMode.equals("null", ignoreCase = true) -> Null

                else -> BinaryFileStream(outputFilePath) // Default
            }
        }
    }
}

val TraceOutputMode.isFileMode: Boolean get() = when (this) {
    is TraceOutputMode.BinaryFileDump,
    is TraceOutputMode.BinaryFileStream,
    is TraceOutputMode.Text
         -> true
    else -> false
}