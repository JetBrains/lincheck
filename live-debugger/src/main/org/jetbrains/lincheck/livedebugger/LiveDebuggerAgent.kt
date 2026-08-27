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

import org.jetbrains.lincheck.tracer.TracingAgent
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.JavaAgentAttachType
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_BLOCKLIST_FILE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_BREAKPOINTS_FILE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_POLICY_BOOTSTRAP
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_REDACTION_FILE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FOPTION
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FORMAT
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_SERVER_PORT
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_START_SERVER
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_HEARTBEAT
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_ENABLE_SSL
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_SSL_TRUSTSTORE_PASSWORD
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_SSL_TRUSTSTORE_PATH
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.classUnderTracing
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.methodUnderTracing
import org.jetbrains.lincheck.jvm.agent.TracingEntryPointMethodVisitorProvider
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.trace.RUNTIME_JVM
import org.jetbrains.lincheck.trace.network.AgentHelloMessage
import org.jetbrains.lincheck.trace.network.LiveDebuggerNotification
import org.jetbrains.lincheck.trace.network.PROTOCOL_VERSION
import org.jetbrains.lincheck.trace.network.TracingServer
import org.jetbrains.lincheck.trace.network.websocket.TracingWebSocketServer
import org.jetbrains.lincheck.trace.serialization.TRACE_VERSION
import org.jetbrains.lincheck.tracer.TraceOutputMode
import org.jetbrains.lincheck.tracer.Tracer
import org.jetbrains.lincheck.tracer.TracingSession
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
        ARGUMENT_BLOCKLIST_FILE,
        ARGUMENT_REDACTION_FILE,
        ARGUMENT_POLICY_BOOTSTRAP,
        ARGUMENT_HEARTBEAT,
        ARGUMENT_START_SERVER,
        ARGUMENT_SERVER_PORT,
        ARGUMENT_ENABLE_SSL,
        ARGUMENT_SSL_TRUSTSTORE_PATH,
        ARGUMENT_SSL_TRUSTSTORE_PASSWORD,
    )
    private val agent = object : TracingAgent() {
        override val modeSystemPropertyName: String = LIVE_DEBUGGER_MODE_PROPERTY

        override val instrumentationMode: InstrumentationMode = InstrumentationMode.LIVE_DEBUGGING

        override fun parseArguments(agentArgs: String?) {
            TraceAgentParameters.parseArgs(agentArgs, ADDITIONAL_ARGS)
            // Policy first: blocklists and redaction templates must be active before any
            // breakpoint source is processed, so Stage-1 registration rejection and capture-time
            // redaction see them. Startup files and the control-plane pull combine by union.
            LiveDebugger.loadBlocklistsFromFile(TraceAgentParameters.blocklistFilePath)
            LiveDebugger.loadRedactionTemplatesFromFile(TraceAgentParameters.redactionFilePath)
            if (TraceAgentParameters.policyBootstrapFromControlPlane) {
                LiveDebugger.bootstrapPolicyFromControlPlane()
            }
            LiveDebugger.loadBreakpointsFromFile(TraceAgentParameters.breakpointsFilePath)
        }

        override fun validateArguments(attachType: JavaAgentAttachType) {
            TraceAgentParameters.validateMode()

            if (classUnderTracing.isNotBlank() || methodUnderTracing.isNotBlank()) {
                error("Class and method arguments are not allowed in live debugger mode")
            }
        }

        override fun postInstallInstrumentationSetup() {
            super.postInstallInstrumentationSetup()

            LiveDebugger.ensureHitLimitCallbackInstalled()
            LiveDebugger.ensureBreakpointExpressionUnsafetyCallbackInstalled()
            LiveDebugger.ensureBreakpointBlockedCallbackInstalled()
            LiveDebugger.ensureHitSuppressedCallbackInstalled()
        }

        override fun setupTracingFromApplicationStartIfRequested() {
            // When a server or heartbeat is enabled, the session lifecycle is driven externally
            // (start/stop via WebSocket commands), so don't auto-start anything here.
            // Otherwise (plain static attach with `output=`), start whole-application file-dump
            // tracing just like the base agent does.
            if (TraceAgentParameters.serverEnabled || TraceAgentParameters.heartBeatEnabled) return
            super.setupTracingFromApplicationStartIfRequested()
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
            if (TraceAgentParameters.heartBeatEnabled) {
                PhoneHomeHeartbeat.start(::connectToControlPlane)
            }
        }

    }

    // entry point for a statically attached java agent
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        agent.premain(agentArgs, inst)
    }

    // entry point for a dynamically attached java agent
    @JvmStatic
    fun agentmain(agentArgs: String?, inst: Instrumentation) {
        agent.agentmain(agentArgs, inst)
    }

    /**
     * Builds this agent's [AgentHelloMessage]: JVM runtime, its version, the agent build,
     * and the trace format it streams.
     */
    private fun agentHello(): AgentHelloMessage = AgentHelloMessage(
        protocolVersion = PROTOCOL_VERSION,
        runtime = RUNTIME_JVM,
        runtimeVersion = System.getProperty("java.version") ?: "unknown",
        agentVersion = LiveDebuggerAgent::class.java.`package`?.implementationVersion ?: "dev",
        timestamp = System.currentTimeMillis(),
        // Advertising the trace-format version lets the client reject a trace stream it cannot deserialize
        // up front, instead of failing mid-stream. This is a separate compatibility axis from
        // `PROTOCOL_VERSION`: the wire protocol frames commands/notifications,
        // while this versions the binary payload of `binaryTraceData`.
        traceVersion = TRACE_VERSION,
        // Advertised so a client can tell the user whether captures are redacted before they arrive.
        attributes = mapOf(AgentHelloMessage.KEY_CAPABILITIES to AgentHelloMessage.CAPABILITY_REDACTION_V1),
    )

    private fun startServer(address: InetSocketAddress?): TracingWebSocketServer? {
        return try {
             val server = object : TracingWebSocketServer(address) {
                override fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean) {
                    if (!LiveDebugger.isDebuggingAllowed) {
                        Logger.warn { "Ignoring startFileTracing: required redaction policy is unavailable" }
                        return
                    }
                    Tracer.launchTracingSession(
                        TracingSession.StartMode.ExternalRequest,
                        TraceOutputMode.BinaryFileStream(traceDumpFilePath),
                        traceDumpFilePath,
                        packTrace,
                    )
                }

                override fun startNetworkTracing() {
                    if (!LiveDebugger.isDebuggingAllowed) {
                        Logger.warn { "Ignoring startNetworkTracing: required redaction policy is unavailable" }
                        return
                    }
                    Tracer.launchTracingSession(
                        TracingSession.StartMode.ExternalRequest,
                        TraceOutputMode.BinaryNetworkStream(this),
                    )
                }

                override fun stopTracing() {
                    Tracer.stopTracing()
                }

                override fun addBreakpoints(breakpoints: List<SnapshotBreakpoint>) {
                    LiveDebugger.addBreakpoints(breakpoints)
                }

                override fun removeBreakpoints(uuids: List<UUID>) {
                    LiveDebugger.removeBreakpoints(uuids)
                }

                override fun addSensitiveAreaBlocklists(blocklists: List<SensitiveAreaBlocklist>) {
                    LiveDebugger.addSensitiveAreaBlocklists(blocklists)
                }

                override fun onConnectionReady() {
                    // A configured-but-invalid required redaction policy cannot recover at
                    // runtime, so no client connection may observe captures: refuse outright.
                    if (!LiveDebugger.isDebuggingAllowed) {
                        Logger.warn { "Closing agent connection: required redaction policy is unavailable" }
                        connection.close()
                        return
                    }
                    // Introduce ourselves first: the client learns the runtime and versions before
                    // any command or notification. This runs while the server holds its lock, so the
                    // hello is guaranteed to be the first frame on the wire.
                    connection.hello(agentHello())
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

                    is LiveDebuggerNotification.BreakpointExpressionUnsafetyDetected ->
                        server.connection.breakpointExpressionUnsafe(
                            notification.breakpointData,
                            notification.slot,
                            notification.safetyViolationMessage,
                            notification.timestamp
                        )

                    is LiveDebuggerNotification.BreakpointBlocked ->
                        server.connection.breakpointBlocked(
                            notification.breakpointData,
                            notification.reason,
                            notification.timestamp
                        )

                    is LiveDebuggerNotification.BreakpointHitSuppressed ->
                        server.connection.breakpointHitSuppressed(
                            notification.breakpointData,
                            notification.blockedFrameClass,
                            notification.reason,
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
            // `https` maps to `wss` by the same rewrite; the URL was already scheme-normalized upstream.
            val wsUrl = controlPlaneUrl
                .replace(Regex("^http"), "ws") + "/api/agent/$agentId"
            val server = this.agent.server as? TracingWebSocketServer
            if (server == null) {
                Logger.warn { "Cannot open reversed connection — no server started" }
                return
            }
            server.makeReversedConnection(URI(wsUrl), ControlPlane.sslSocketFactory(), ControlPlane.agentAuthHeaders())
            Logger.info { "Opened reversed WS connection to $wsUrl" }
        } catch (e: Exception) {
            Logger.error(e) { "Failed to open reversed WS connection to control plane" }
            PhoneHomeHeartbeat.resetConnectTriggered()
        }
    }
}
