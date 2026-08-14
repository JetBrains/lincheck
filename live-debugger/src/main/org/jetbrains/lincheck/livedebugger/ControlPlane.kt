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
import org.jetbrains.lincheck.settings.PolicyBundle
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.parsePolicyBundleJson
import org.jetbrains.lincheck.util.Logger
import org.jetbrains.lincheck.util.TlsTrust
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

private const val POLICY_PATH = "/api/policy"

private const val HTTP_TIMEOUT_MS = 5_000
private const val MAX_ATTEMPTS = 3
private const val RETRY_DELAY_MS = 1_000L

/** Env var carrying an optional shared secret to authenticate this agent to the control plane. */
internal const val ENV_AGENT_SECRET = "LIVE_DEBUGGER_AGENT_SECRET"

/** Header carrying the shared secret, when configured. Part of the wire protocol — must match the server. */
internal const val AGENT_SECRET_HEADER = "X-AppGlass-Agent-Secret"

/**
 * The agent's side of its connections to the control plane: where the control plane is, how to talk to
 * it securely and authenticate to it, and the policy it hands out at startup.
 *
 * TLS comes from the `enableSsl`, `sslTruststorePath` and `sslTruststorePassword` agent arguments and
 * the agent credential from [ENV_AGENT_SECRET]; both cover every leg — the HTTP calls made here and by
 * [PhoneHomeHeartbeat], plus the reversed WebSocket connection — so a single switch configures all
 * control-plane communication.
 */
internal object ControlPlane {

    /** `true` when the agent was started with `enableSsl=on`. */
    val sslEnabled: Boolean get() = TraceAgentParameters.sslEnabled

    /**
     * Normalizes the configured control-plane base URL, upgrading `http://` to `https://` when TLS is
     * enabled.
     *
     * The upgrade means the deployment does not have to template a different URL per TLS mode; an
     * explicit `https://` URL is honored regardless of the switch.
     */
    fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (!sslEnabled || trimmed.startsWith("https://")) return trimmed
        val upgraded = if (trimmed.startsWith("http://")) "https://" + trimmed.removePrefix("http://") else trimmed
        if (upgraded != trimmed) Logger.info { "enableSsl=on: using $upgraded instead of $trimmed" }
        return upgraded
    }

    /** Socket factory trusting the configured CA, or `null` when TLS is off. */
    fun sslSocketFactory(): SSLSocketFactory? =
        if (sslEnabled) {
            TlsTrust.sslSocketFactory(
                truststorePath = TraceAgentParameters.sslTruststorePath,
                truststorePassword = TraceAgentParameters.sslTruststorePassword,
            )
        } else null

    /**
     * Headers authenticating this agent to the control plane: the shared secret when [ENV_AGENT_SECRET]
     * is set, nothing otherwise (an unprotected control plane expects no credential).
     *
     * The same headers go on every leg — the policy pull, the heartbeats, and the reversed WebSocket
     * handshake — because one control-plane provider guards all of the agent-facing routes.
     */
    fun agentAuthHeaders(): Map<String, String> {
        val secret = System.getenv(ENV_AGENT_SECRET)
        return if (secret.isNullOrBlank()) emptyMap() else mapOf(AGENT_SECRET_HEADER to secret)
    }

    /**
     * Prepares [connection] for a call to the control plane: CA truststore (HTTPS only) plus the agent
     * credential. Every agent HTTP call goes through here, so no leg can silently skip authentication.
     */
    fun configureConnection(connection: HttpURLConnection, authHeaders: Map<String, String> = agentAuthHeaders()) {
        if (connection is HttpsURLConnection) {
            sslSocketFactory()?.let { connection.sslSocketFactory = it }
        }
        authHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
    }

    /**
     * Pulls the sensitive-area policy from the control plane at agent startup
     * (`policyBootstrap=controlPlane`), retrying briefly ([MAX_ATTEMPTS] attempts, [RETRY_DELAY_MS] apart).
     *
     * A `GET` against [POLICY_PATH] on the control-plane base URL, performed in `premain` before any
     * breakpoint source is processed. The control plane is the policy custody point; the agent remains
     * the authoritative enforcer.
     *
     * @return the pulled blocklists, or `null` if [LIVE_DEBUGGER_CONTROL_PLANE_URL_ENV_VAR] is unset
     *   or the control plane could not be reached / responded with an error within the retry budget.
     */
    fun fetchPolicyBlocklists(): List<SensitiveAreaBlocklist>? {
        val baseUrl = System.getenv(LIVE_DEBUGGER_CONTROL_PLANE_URL_ENV_VAR)
        if (baseUrl.isNullOrBlank()) {
            Logger.error {
                "policyBootstrap=controlPlane requires " +
                    "the $LIVE_DEBUGGER_CONTROL_PLANE_URL_ENV_VAR environment variable"
            }
            return null
        }
        return pullPolicyBlocklists("${normalizeBaseUrl(baseUrl)}$POLICY_PATH", agentAuthHeaders())
    }

    /**
     * The retry loop behind [fetchPolicyBlocklists], taking [url] and [headers] explicitly so the pull
     * is testable without process environment.
     */
    internal fun pullPolicyBlocklists(url: String, headers: Map<String, String>): List<SensitiveAreaBlocklist>? {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val bundle = fetchPolicy(url, headers)
                Logger.info {
                    "Pulled policy v${bundle.version} (${bundle.blocklists.size} blocklist(s)) from $url"
                }
                return bundle.blocklists
            } catch (e: Exception) {
                Logger.warn { "Policy pull attempt ${attempt + 1}/$MAX_ATTEMPTS from $url failed: ${e.message}" }
                if (attempt < MAX_ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS)
            }
        }
        Logger.error { "Failed to pull policy from $url after $MAX_ATTEMPTS attempts" }
        return null
    }

    /**
     * Uses [HttpURLConnection] and a manual JSON parse to avoid pulling an HTTP/JSON library onto the
     * agent classpath, mirroring [PhoneHomeHeartbeat].
     */
    private fun fetchPolicy(url: String, headers: Map<String, String>): PolicyBundle {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            configureConnection(connection, headers)
            connection.requestMethod = "GET"
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            val code = connection.responseCode
            check(code in 200..299) { "GET $url returned HTTP $code" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parsePolicyBundleJson(body)
        } finally {
            connection.disconnect()
        }
    }
}
