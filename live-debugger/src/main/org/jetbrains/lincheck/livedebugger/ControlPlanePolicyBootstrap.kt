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

import org.jetbrains.lincheck.settings.PolicyBundle
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.parsePolicyBundleJson
import org.jetbrains.lincheck.util.Logger
import java.net.HttpURLConnection
import java.net.URI

private const val ENV_CONTROL_PLANE_URL = "LIVE_DEBUGGER_CONTROL_PLANE_URL"

private const val POLICY_PATH = "/api/policy"

private const val HTTP_TIMEOUT_MS = 5_000
private const val MAX_ATTEMPTS = 3
private const val RETRY_DELAY_MS = 1_000L

/**
 * Pulls the sensitive-area policy from the control plane at agent startup
 * (`policyBootstrap=controlPlane`).
 *
 * A `GET` against [POLICY_PATH] on the control-plane base URL (env [ENV_CONTROL_PLANE_URL], the same
 * variable the heartbeat uses), performed in `premain` before any breakpoint source is processed.
 * The control plane is the policy custody point; the agent remains the authoritative enforcer.
 *
 * Uses [HttpURLConnection] and a manual JSON parse to avoid pulling an HTTP/JSON library onto the
 * agent classpath, mirroring [PhoneHomeHeartbeat].
 */
internal object ControlPlanePolicyBootstrap {

    /**
     * Fetches the policy bundle, retrying briefly ([MAX_ATTEMPTS] attempts, [RETRY_DELAY_MS] apart).
     *
     * @return the pulled blocklists, or `null` if [ENV_CONTROL_PLANE_URL] is unset or the control
     *   plane could not be reached / responded with an error within the retry budget.
     */
    fun fetchBlocklists(): List<SensitiveAreaBlocklist>? {
        val baseUrl = System.getenv(ENV_CONTROL_PLANE_URL)
        if (baseUrl.isNullOrBlank()) {
            Logger.error { "policyBootstrap=controlPlane requires the $ENV_CONTROL_PLANE_URL environment variable" }
            return null
        }
        val url = "${baseUrl.trimEnd('/')}$POLICY_PATH"
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val bundle = fetch(url)
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

    private fun fetch(url: String): PolicyBundle {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
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
