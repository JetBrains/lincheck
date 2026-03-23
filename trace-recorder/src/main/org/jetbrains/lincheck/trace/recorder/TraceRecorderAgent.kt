/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.recorder

import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.JavaAgentAttachType
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_BREAKPOINTS_FILE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_EXCLUDE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FOPTION
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FORMAT
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_INCLUDE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_PACK
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_START_SERVER
import org.jetbrains.lincheck.jvm.agent.TracingEntryPointMethodVisitorProvider
import org.jetbrains.lincheck.trace.network.TracingServer
import org.jetbrains.lincheck.trace.network.ws.TracingWebSocketServer
import org.jetbrains.lincheck.tracer.TraceOutputMode
import org.jetbrains.lincheck.tracer.Tracer
import org.jetbrains.lincheck.tracer.TracerAgent
import org.jetbrains.lincheck.tracer.TracingSession
import org.jetbrains.lincheck.util.Logger
import org.jetbrains.lincheck.util.TRACE_RECORDER_MODE_PROPERTY
import java.lang.instrument.Instrumentation
import java.net.InetSocketAddress

/**
 * Trace recorder JVM agent.
 *
 * Trace recorder captures the execution trace of a program and saves it into a file.
 */
private const val DEFAULT_TRACING_PORT = 9997

internal object TraceRecorderAgent {

    // Allowed additional arguments
    private val ADDITIONAL_ARGS = listOf(
        ARGUMENT_FORMAT,
        ARGUMENT_FOPTION,
        ARGUMENT_INCLUDE,
        ARGUMENT_EXCLUDE,
        ARGUMENT_PACK,
        ARGUMENT_BREAKPOINTS_FILE,
        ARGUMENT_START_SERVER,
    )
    
    private var server: TracingServer? = null

    private val agent = object : TracerAgent() {
        override val modeSystemPropertyName: String = TRACE_RECORDER_MODE_PROPERTY

        override val instrumentationMode: InstrumentationMode = InstrumentationMode.TRACE_RECORDING

        override fun parseArguments(agentArgs: String?) {
            TraceAgentParameters.parseArgs(agentArgs, ADDITIONAL_ARGS)
        }

        override fun validateArguments(attachType: JavaAgentAttachType) {
            TraceAgentParameters.validateMode()

            if (attachType == JavaAgentAttachType.STATIC) {
                TraceAgentParameters.validateClassAndMethodArgumentsAreProvided()
            }
        }

        override val tracingEntryPointMethodVisitorProvider: TracingEntryPointMethodVisitorProvider
            get() = ::TraceRecorderMethodTransformer
    }

    private fun createTracingServer(): TracingServer? {
        try {
            val server = object : TracingWebSocketServer(InetSocketAddress(DEFAULT_TRACING_PORT)) {
                override fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean) {
                    Tracer.startTracing(
                        TraceOutputMode.BinaryFileStream(traceDumpFilePath),
                        TracingSession.StartMode.Dynamic,
                    )
                }

                override fun startNetworkTracing() {
                    Tracer.startTracing(TraceOutputMode.BinaryNetworkStream(this), TracingSession.StartMode.Dynamic)
                }

                override fun stopTracing() {
                    Tracer.stopTracing()
                }

                override fun addBreakpoints(breakpoints: List<String>) {
                    // Not supported in trace recorder mode
                }

                override fun removeBreakpoints(breakpoints: List<String>) {
                    // Not supported in trace recorder mode
                }

                override fun onDisconnected() {
                    // No cleanup needed for trace recorder
                }
            }
            Logger.info { "Started trace streaming server on port $DEFAULT_TRACING_PORT" }
            return server
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot start trace server" }
            return null
        }
    }

    // entry point for a statically attached java agent
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        agent.premain(agentArgs, inst)
        if (TraceAgentParameters.getArg(ARGUMENT_START_SERVER)?.lowercase() == "true") {
            server = createTracingServer()
        }
    }

    // entry point for a dynamically attached java agent
    @JvmStatic
    fun agentmain(agentArgs: String?, inst: Instrumentation) {
        agent.agentmain(agentArgs, inst)
    }
}
