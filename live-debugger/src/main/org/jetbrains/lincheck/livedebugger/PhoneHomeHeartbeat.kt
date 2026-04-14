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
 * Note: These heartbeats are not used to check if a connection is open, 
 * their only purpose is to show that this agent is live. 
 * Connection lost events are handled by the WebSocket server.
 *
 * Requires the following environment variables to be set:
 * - `POD_NAME` — the Kubernetes pod name (typically via Downward API)
 * - `POD_NAMESPACE` — the Kubernetes namespace (typically via Downward API)
 * - `POD_IP` — the pod's IP address (typically via Downward API)
 * - `LIVE_DEBUGGER_CONTROL_PLANE_URL` — the base URL of the control plane service
 */
internal object PhoneHomeHeartbeat {

    fun start() {
        val podName = System.getenv(ENV_POD_NAME)
            ?: error("phoneHome=on requires the $ENV_POD_NAME environment variable to be set")
        val namespace = System.getenv(ENV_POD_NAMESPACE)
            ?: error("phoneHome=on requires the $ENV_POD_NAMESPACE environment variable to be set")
        val podIp = System.getenv(ENV_POD_IP)
            ?: error("phoneHome=on requires the $ENV_POD_IP environment variable to be set")
        val controlPlaneUrl = System.getenv(ENV_CONTROL_PLANE_URL)
            ?: error("phoneHome=on requires the $ENV_CONTROL_PLANE_URL environment variable to be set")

        val heartbeatUrl = "${controlPlaneUrl.trimEnd('/')}/heartbeat"
        val serverPort = TraceAgentParameters.serverPort
        val body = """{"podName":"$podName","namespace":"$namespace","podIp":"$podIp","serverPort":$serverPort}"""

        val thread = Thread({
            Logger.debug { "Phone-home heartbeat started: $heartbeatUrl" }
            while (true) {
                try {
                    sendHeartbeat(heartbeatUrl, body)
                } catch (e: Exception) {
                    Logger.warn { "Phone-home heartbeat failed: ${e.message}" }
                }
                try {
                    Thread.sleep(DEFAULT_HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "live-debugger-phone-home")
        thread.isDaemon = true
        thread.start()
    }

    private fun sendHeartbeat(url: String, body: String) {
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
            }
        } finally {
            connection.disconnect()
        }
    }
}
