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
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.classUnderTracing
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.methodUnderTracing
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.traceDumpFilePath
import org.jetbrains.lincheck.jvm.agent.TracingEntryPointMethodVisitorProvider
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.trace.network.LiveDebuggerNotification
import org.jetbrains.lincheck.trace.network.TracingServer
import org.jetbrains.lincheck.trace.network.websocket.TracingWebSocketServer
import org.jetbrains.lincheck.tracer.TraceOutputMode
import org.jetbrains.lincheck.util.LIVE_DEBUGGER_MODE_PROPERTY
import org.jetbrains.lincheck.util.Logger
import sun.nio.ch.lincheck.BreakpointStorage
import java.lang.instrument.Instrumentation
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID

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

            if (classUnderTracing.isNotBlank() || methodUnderTracing.isNotBlank()) {
                error("Class and method arguments are not allowed in live debugger mode")
            }
        }

        override val tracingEntryPointMethodVisitorProvider: TracingEntryPointMethodVisitorProvider? = null

        override fun createTracingServer(): TracingServer? {
            return if (TraceAgentParameters.serverEnabled) {
                startServer(InetSocketAddress(TraceAgentParameters.serverPort))
            } else if (TraceAgentParameters.heartBeatEnabled) {
                startServer(address = null)
            } else {
                null
            }
        }

        override fun startTracingServerIfRequested() {
            val wsServer = createTracingServer() as TracingWebSocketServer?
            if (wsServer != null) {
                server = wsServer
                Runtime.getRuntime().addShutdownHook(Thread { wsServer.close() })
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
            PhoneHomeHeartbeat.start(::connectToControlPlane)
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

    private fun startServer(address: InetSocketAddress?): TracingWebSocketServer? {
        return try {
             val server = object : TracingWebSocketServer(address) {
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

                override fun stopTracing() = LiveDebugger.stopRecording()

                override fun addBreakpoints(breakpoints: List<SnapshotBreakpoint>) = LiveDebugger.addBreakpoints(breakpoints)

                override fun removeBreakpoints(uuids: List<UUID>) = LiveDebugger.removeBreakpoints(uuids)

                override fun onConnectionReady() {
                    PhoneHomeHeartbeat.setConnectTriggered()
                }

                override fun onDisconnected() {
                    LiveDebugger.removeAllBreakpoints()
                    BreakpointStorage.clear()
                    PhoneHomeHeartbeat.resetConnectTriggered()
                }
            }
            if (address != null) {
                Logger.info { "Started trace server on port ${address.port}" }
            } else {
                Logger.info { "Initialized trace server (no listening socket, reversed connections only)" }
            }
            LiveDebugger.installNotificationListener { notification ->
                when (notification) {
                    is LiveDebuggerNotification.BreakpointHitLimitReached ->
                        server.connection.hitLimitReached(
                            notification.breakpointData,
                            notification.timestamp
                        )

                    is LiveDebuggerNotification.BreakpointConditionUnsafetyDetected ->
                        server.connection.conditionUnsafe(
                            notification.breakpointData,
                            notification.safetyViolationMessage,
                            notification.timestamp
                        )
                }
            }
            server
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot start trace server" }
            null
        }
    }

    /**
     * Called by the heartbeat thread when the control plane responds with `connect=true`.
     * Opens a reversed WebSocket connection to the control plane at
     * `/api/agent/{agentId}`.
     */
    private fun connectToControlPlane(controlPlaneUrl: String, agentId: String) {
        try {
            val wsUrl = controlPlaneUrl
                .replace(Regex("^http"), "ws") + "/api/agent/$agentId"
            val server = this.agent.server as? TracingWebSocketServer
            if (server == null) {
                Logger.warn { "Cannot open reversed connection — no server started" }
                return
            }
            server.makeReversedConnection(URI(wsUrl))
            Logger.info { "Opened reversed WS connection to $wsUrl" }
        } catch (e: Exception) {
            Logger.error(e) { "Failed to open reversed WS connection to control plane" }
            PhoneHomeHeartbeat.resetConnectTriggered()
        }
    }

    @JvmStatic
    private fun installCallbacks() {
        LiveDebugger.ensureHitLimitCallbackInstalled()
        LiveDebugger.ensureConditionUnsafetyCallbackInstalled()
    }
}