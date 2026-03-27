/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.livedebugger

import org.jetbrains.lincheck.tracer.TracerAgent
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.JavaAgentAttachType
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_BREAKPOINTS_FILE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FOPTION
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FORMAT
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_PACK
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_SERVER_PORT
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_START_SERVER
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_HEARTBEAT
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.classUnderTraceDebugging
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.methodUnderTraceDebugging
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.traceDumpFilePath
import org.jetbrains.lincheck.jvm.agent.TracingEntryPointMethodVisitorProvider
import org.jetbrains.lincheck.trace.network.LiveDebuggerNotification
import org.jetbrains.lincheck.trace.network.TracingServer
import org.jetbrains.lincheck.trace.network.websocket.TracingWebSocketServer
import org.jetbrains.lincheck.tracer.TraceOutputMode
import org.jetbrains.lincheck.util.LIVE_DEBUGGER_MODE_PROPERTY
import org.jetbrains.lincheck.util.Logger
import sun.nio.ch.lincheck.BreakpointStorage
import java.lang.instrument.Instrumentation
import java.net.InetSocketAddress

/**
 * Live debugging JVM agent.
 *
 * Live debugging allows the insertion of non-suspending breakpoints
 * that capture a snapshot of the program's state at the specified code location.
 */
internal object LiveDebuggerAgent {

    // Allowed additional arguments
    private val ADDITIONAL_ARGS = listOf(
        ARGUMENT_FORMAT,
        ARGUMENT_FOPTION,
        ARGUMENT_BREAKPOINTS_FILE,
        ARGUMENT_HEARTBEAT,
        ARGUMENT_START_SERVER,
        ARGUMENT_SERVER_PORT,
    )
    private val agent = object : TracerAgent() {
        override val modeSystemPropertyName: String = LIVE_DEBUGGER_MODE_PROPERTY

        override val instrumentationMode: InstrumentationMode = InstrumentationMode.LIVE_DEBUGGING

        override fun parseArguments(agentArgs: String?) {
            TraceAgentParameters.parseArgs(agentArgs, ADDITIONAL_ARGS)
            LiveDebugger.loadBreakpointsFromFile(TraceAgentParameters.breakpointsFilePath)
        }

        override fun validateArguments(attachType: JavaAgentAttachType) {
            TraceAgentParameters.validateMode()

            if (classUnderTraceDebugging.isNotBlank() || methodUnderTraceDebugging.isNotBlank()) {
                error("Class and method arguments are not allowed in live debugger mode")
            }
        }

        override val tracingEntryPointMethodVisitorProvider: TracingEntryPointMethodVisitorProvider? = null

        override fun createTracingServer(): TracingServer? {
            try {
                val port = TraceAgentParameters.serverPort
                val server = object : TracingWebSocketServer(InetSocketAddress(port)) {
                    override fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean) {
                        LiveDebugger.startRecording(
                            TraceOutputMode.BinaryFileStream(traceDumpFilePath),
                            traceDumpFilePath,
                            packTrace,
                        )
                    }

                    override fun startNetworkTracing() {
                        LiveDebugger.startRecording(TraceOutputMode.BinaryNetworkStream(this))
                    }

                    override fun stopTracing() {
                        LiveDebugger.stopRecording()
                    }

                    override fun addBreakpoints(breakpoints: List<String>) {
                        LiveDebugger.addBreakpoints(breakpoints)
                    }

                    override fun removeBreakpoints(breakpoints: List<String>) {
                        LiveDebugger.removeBreakpoints(breakpoints)
                    }

                    override fun onDisconnected() {
                        LiveDebugger.removeAllBreakpoints()
                        BreakpointStorage.clear()
                    }
                }
                Logger.info { "Started trace server on port $port" }
                LiveDebugger.installNotificationListener { notification ->
                    when (notification) {
                        is LiveDebuggerNotification.BreakpointHitLimitReached ->
                            server.connection.hitLimitReached(notification.breakpointData, notification.timestamp)
                        is LiveDebuggerNotification.BreakpointConditionUnsafetyDetected ->
                            server.connection.conditionUnsafe(notification.breakpointData, notification.timestamp)
                    }
                }
                return server
            } catch (t: Throwable) {
                Logger.error(t) { "Cannot start trace server" }
                return null
            }
        }

    }

    // entry point for a statically attached java agent
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        agent.premain(agentArgs, inst)
        postInstallSetup()
    }

    // entry point for a dynamically attached java agent
    @JvmStatic
    fun agentmain(agentArgs: String?, inst: Instrumentation) {
        agent.agentmain(agentArgs, inst)
        postInstallSetup()
    }

    private fun postInstallSetup() {
        installCallbacks()

        if (TraceAgentParameters.heartBeatEnabled) {
            PhoneHomeHeartbeat.start()
        }
        
        if (traceDumpFilePath != null) {
            
            val mode = TraceOutputMode.parse(
                outputMode = TraceAgentParameters.getArg(ARGUMENT_FORMAT),
                outputOption = TraceAgentParameters.getArg(ARGUMENT_FOPTION),
                outputFilePath = traceDumpFilePath,
            )
            val packTrace = (TraceAgentParameters.getArg(ARGUMENT_PACK) ?: "true").toBoolean()

            LiveDebugger.startRecording(mode, traceDumpFilePath, packTrace)
        }

    }

    @JvmStatic
    private fun installCallbacks() {
        LiveDebugger.ensureHitLimitCallbackInstalled()
    }
}