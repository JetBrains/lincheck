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

import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.util.Logger
import java.net.HttpURLConnection
import java.net.URI

private const val ENV_POD_NAME = "POD_NAME"
private const val ENV_POD_NAMESPACE = "POD_NAMESPACE"
private const val ENV_POD_IP = "POD_IP"
private const val ENV_CONTROL_PLANE_URL = "LIVE_DEBUGGER_CONTROL_PLANE_URL"

private const val DEFAULT_HEARTBEAT_INTERVAL_MS = 10_000L
private const val HTTP_TIMEOUT_MS = 5_000

/**
 * Sends periodic heartbeat messages to the live-debugger control plane,
 * registering this agent so that the control plane knows which agents are available.
 *
 * When the control plane responds with `{"connect":true}`, the heartbeat invokes
 * the provided [onConnectRequested] callback so the agent can open a reversed
 * WebSocket connection back to the control plane.
 *
 * Requires the following environment variables to be set:
 * - `POD_NAME` — the Kubernetes pod name (typically via Downward API)
 * - `POD_NAMESPACE` — the Kubernetes namespace (typically via Downward API)
 * - `POD_IP` — the pod's IP address (typically via Downward API)
 * - `LIVE_DEBUGGER_CONTROL_PLANE_URL` — the base URL of the control plane service
 */
internal object PhoneHomeHeartbeat {

    @Volatile
    private var connectTriggered = false

    fun resetConnectTriggered() {
        connectTriggered = false
        Logger.info { "Connect-triggered flag reset, ready for new connect request" }
    }

    fun setConnectTriggered() {
        connectTriggered = true
        Logger.info { "Connect-triggered flag set by direct connection" }
    }

    fun start(onConnectRequested: (controlPlaneUrl: String, agentId: String) -> Unit) {
        val podName = System.getenv(ENV_POD_NAME)
            ?: error("phoneHome=on requires the $ENV_POD_NAME environment variable to be set")
        val namespace = System.getenv(ENV_POD_NAMESPACE)
            ?: error("phoneHome=on requires the $ENV_POD_NAMESPACE environment variable to be set")
        val podIp = System.getenv(ENV_POD_IP)
            ?: error("phoneHome=on requires the $ENV_POD_IP environment variable to be set")
        val controlPlaneUrl = System.getenv(ENV_CONTROL_PLANE_URL)
            ?: error("phoneHome=on requires the $ENV_CONTROL_PLANE_URL environment variable to be set")

        val heartbeatUrl = "${controlPlaneUrl.trimEnd('/')}/api/heartbeat"
        val serverPort = TraceAgentParameters.serverPort
        val body = """{"podName":"$podName","namespace":"$namespace","podIp":"$podIp","serverPort":$serverPort}"""

        val thread = Thread({
            Logger.debug { "Phone-home heartbeat started: $heartbeatUrl" }
            while (true) {
                try {
                    val response = sendHeartbeat(heartbeatUrl, body)
                    if (response.connect && !connectTriggered) {
                        connectTriggered = true
                        Logger.info { "Control plane requested connection (agentId=${response.agentId})" }
                        onConnectRequested(controlPlaneUrl.trimEnd('/'), response.agentId)
                    }
                } catch (e: Exception) {
                    Logger.warn { "Phone-home heartbeat failed: ${e.message}" }
                }
                Thread.sleep(DEFAULT_HEARTBEAT_INTERVAL_MS)
            }
        }, "live-debugger-phone-home")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * Parsed heartbeat response from the control plane.
     * We parse manually to avoid pulling in a JSON library on the agent classpath.
     */
    internal data class HeartbeatResult(val connect: Boolean, val agentId: String)

    /**
     * Sends a heartbeat and parses the JSON response.
     */
    private fun sendHeartbeat(url: String, body: String): HeartbeatResult {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                Logger.warn { "Phone-home heartbeat returned HTTP $responseCode" }
                return HeartbeatResult(connect = false, agentId = "")
            }
            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            return parseHeartbeatResponse(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Minimal JSON parser for the heartbeat response.
     * Avoids pulling in a JSON library on the agent classpath.
     */
    internal fun parseHeartbeatResponse(json: String): HeartbeatResult {
        val connect = Regex(""""connect"\s*:\s*(true|false)""").find(json)
            ?.groupValues?.get(1) == "true"
        val agentId = Regex(""""agentId"\s*:\s*"([^"]*)"""").find(json)
            ?.groupValues?.get(1) ?: ""
        return HeartbeatResult(connect = connect, agentId = agentId)
    }
}
