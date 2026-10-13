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
import org.jetbrains.lincheck.settings.LIVE_DEBUGGER_CONTROL_PLANE_URL_ENV_VAR
import org.jetbrains.lincheck.settings.POLICY_SOURCE_CONTROL_PLANE
import org.jetbrains.lincheck.settings.POLICY_SOURCE_DEPLOYMENT
import org.jetbrains.lincheck.util.Logger
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

private const val ENV_NAME = "NAME"
private const val ENV_NAMESPACE = "NAMESPACE"

private const val DEFAULT_HEARTBEAT_INTERVAL_MS = 10_000L
private const val HTTP_TIMEOUT_MS = 5_000

/** Unanswered for this long, a control-plane policy may be stale: three missed heartbeats, the control plane's TTL. */
internal const val CONTROL_PLANE_LOSS_TIMEOUT_MS = 3 * DEFAULT_HEARTBEAT_INTERVAL_MS

/**
 * Sends periodic heartbeat messages to the live-debugger control plane,
 * registering this agent so that the control plane knows which agents are available.
 *
 * When the control plane responds with `{"connect":true}`, the heartbeat invokes
 * the provided [onConnectRequested] callback so the agent can open a reversed
 * WebSocket connection back to the control plane.
 *
 * With `policySource=controlPlane` every heartbeat reports the policy version in force, and the control plane
 * answers with its current policy whenever that differs; it is applied before any connect request is acted on.
 * Debugging closes, through [onDebuggingClosed] too, when that policy fails to apply or the control plane leaves
 * heartbeats unanswered for [CONTROL_PLANE_LOSS_TIMEOUT_MS]; the next answered heartbeat re-delivers it.
 *
 * Requires the following environment variables:
 * - `NAME` — a stable identifier for this agent (e.g. the Kubernetes pod name via Downward API)
 * - `LIVE_DEBUGGER_CONTROL_PLANE_URL` — the base URL of the control plane service
 *
 * Optional:
 * - `NAMESPACE` — a grouping label for the agent (e.g. the Kubernetes namespace). Defaults to empty.
 * - `LIVE_DEBUGGER_AGENT_SECRET` — a shared secret sent as a header on every heartbeat request
 *   (see [ControlPlane.agentAuthHeaders]), for control planes that require agents to authenticate themselves.
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

    fun start(
        onConnectRequested: (controlPlaneUrl: String, agentId: String) -> Unit,
        onDebuggingClosed: () -> Unit,
    ) {
        val name = System.getenv(ENV_NAME)
            ?: error("phoneHome=on requires the $ENV_NAME environment variable to be set")
        val namespace = System.getenv(ENV_NAMESPACE).orEmpty()
        val controlPlaneUrl = ControlPlane.normalizeBaseUrl(
            System.getenv(LIVE_DEBUGGER_CONTROL_PLANE_URL_ENV_VAR)
                ?: error(
                    "phoneHome=on requires the $LIVE_DEBUGGER_CONTROL_PLANE_URL_ENV_VAR environment variable to be set"
                )
        )

        val heartbeatUrl = "$controlPlaneUrl/api/heartbeat"
        val serverPort = TraceAgentParameters.serverPort
        val policyFromControlPlane = TraceAgentParameters.policyFromControlPlane

        val thread = Thread({
            Logger.debug { "Phone-home heartbeat started: $heartbeatUrl" }
            var lastAnsweredAt = System.nanoTime()
            while (true) {
                try {
                    val policyVersion = if (policyFromControlPlane) LiveDebugger.controlPlanePolicyVersion else null
                    val body = requestBody(name, namespace, serverPort, policyFromControlPlane, policyVersion)
                    val response = sendHeartbeat(heartbeatUrl, body)
                    if (response == null) {
                        val unansweredMs = (System.nanoTime() - lastAnsweredAt) / 1_000_000
                        if (policyFromControlPlane && isControlPlaneLost(unansweredMs) && policyVersion != null) {
                            LiveDebugger.revokeControlPlanePolicy()
                            onDebuggingClosed()
                        }
                        continue
                    }
                    lastAnsweredAt = System.nanoTime()
                    if (policyFromControlPlane && response.policy != null) {
                        LiveDebugger.applyControlPlanePolicy(response.policy)
                        if (!LiveDebugger.isDebuggingAllowed) onDebuggingClosed()
                    }
                    if (response.connect && !connectTriggered) {
                        connectTriggered = true
                        Logger.info { "Control plane requested connection (agentId=${response.agentId})" }
                        onConnectRequested(controlPlaneUrl, response.agentId)
                    }
                } catch (e: Exception) {
                    Logger.warn { "Phone-home heartbeat failed: ${e.message}" }
                } finally {
                    Thread.sleep(DEFAULT_HEARTBEAT_INTERVAL_MS)
                }
            }
        }, "live-debugger-phone-home")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * Parsed heartbeat response from the control plane.
     * We parse manually to avoid pulling in a JSON library on the agent classpath.
     *
     * @property policy the control plane's current policy, sent only when it differs from the reported one.
     */
    internal data class HeartbeatResult(val connect: Boolean, val agentId: String, val policy: ControlPlanePolicy? = null)

    internal fun requestBody(
        name: String,
        namespace: String,
        serverPort: Int,
        policyFromControlPlane: Boolean,
        policyVersion: String?,
    ): String {
        val source = if (policyFromControlPlane) POLICY_SOURCE_CONTROL_PLANE else POLICY_SOURCE_DEPLOYMENT
        val version = policyVersion?.let { ",\"policyVersion\":\"$it\"" }.orEmpty()
        return """{"name":"$name","namespace":"$namespace","serverPort":$serverPort,"policySource":"$source"$version}"""
    }

    internal fun isControlPlaneLost(unansweredMs: Long): Boolean = unansweredMs >= CONTROL_PLANE_LOSS_TIMEOUT_MS

    /** Sends a heartbeat; `null` unless the control plane gave a well-formed answer, which then counts as unanswered. */
    private fun sendHeartbeat(url: String, body: String): HeartbeatResult? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            ControlPlane.configureConnection(connection)
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                Logger.warn { "Phone-home heartbeat returned HTTP $responseCode" }
                return null
            }
            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            return parseHeartbeatResponse(responseBody)
                ?: null.also { Logger.warn { "Phone-home heartbeat got a malformed answer" } }
        } catch (e: Exception) {
            Logger.warn { "Phone-home heartbeat failed: ${e.message}" }
            return null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Minimal JSON parser for the heartbeat response, avoiding a JSON library on the agent classpath.
     * `null` for anything but a well-formed answer: `connect`, a non-empty `agentId`, and the three policy fields
     * all present or all absent. A proxy's error page or an incompatible control plane must not pass for an answer.
     */
    internal fun parseHeartbeatResponse(json: String): HeartbeatResult? {
        val connect = Regex(""""connect"\s*:\s*(true|false)""").find(json)?.groupValues?.get(1) ?: return null
        val agentId = stringField(json, "agentId")?.takeIf { it.isNotEmpty() } ?: return null
        val version = stringField(json, "policyVersion")
        val redactionIni = base64Field(json, "redactionIniBase64")
        val blocklistsIni = base64Field(json, "blocklistsIniBase64")
        val policy = when {
            version.isNullOrEmpty() && redactionIni == null && blocklistsIni == null -> null
            version.isNullOrEmpty() || redactionIni == null || blocklistsIni == null -> return null
            else -> ControlPlanePolicy(version, redactionIni, blocklistsIni)
        }
        return HeartbeatResult(connect = connect == "true", agentId = agentId, policy = policy)
    }

    private fun stringField(json: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)

    /** The decoded field, or `null` when it is absent or not valid Base64-wrapped UTF-8. */
    private fun base64Field(json: String, name: String): String? =
        stringField(json, name)?.let { runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
}
