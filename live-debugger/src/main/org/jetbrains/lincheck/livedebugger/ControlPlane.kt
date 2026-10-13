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
import org.jetbrains.lincheck.util.TlsTrust
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/** The control plane's blocklist and data-redaction policy for this agent's environment, in the startup-file formats. */
internal data class ControlPlanePolicy(val version: String, val redactionIni: String, val blocklistsIni: String)

/** Env var carrying an optional shared secret to authenticate this agent to the control plane. */
internal const val ENV_AGENT_SECRET = "LIVE_DEBUGGER_AGENT_SECRET"

/** Header carrying the shared secret, when configured. Part of the wire protocol — must match the server. */
internal const val AGENT_SECRET_HEADER = "X-AppGlass-Agent-Secret"

/**
 * The agent's side of its connections to the control plane: where the control plane is, how to talk to
 * it securely and authenticate to it.
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
     * The same headers go on every leg — the heartbeats and the reversed WebSocket handshake —
     * because one control-plane provider guards all of the agent-facing routes.
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
}
