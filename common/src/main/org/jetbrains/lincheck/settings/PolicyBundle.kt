/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.settings

import java.security.MessageDigest

/**
 * The sensitive-area policy the control plane serves at `GET /api/policy` and the agent pulls at
 * startup (`policyBootstrap=controlPlane`). Carries the active [blocklists] plus a content-derived
 * [version] agents can use to detect that the policy has not changed.
 *
 * The control plane is the policy custody point; the agent remains the authoritative enforcer.
 *
 * @property version content hash of the blocklists (see [of]); opaque to the agent.
 * @property blocklists the blocklists to enforce, in serving order.
 */
class PolicyBundle(
    val version: String,
    val blocklists: List<SensitiveAreaBlocklist>,
) {
    companion object {
        /** Builds a bundle with a deterministic content [version] derived from [blocklists]. */
        fun of(blocklists: List<SensitiveAreaBlocklist>): PolicyBundle =
            PolicyBundle(versionOf(blocklists), blocklists)

        /** Short SHA-256 prefix over the canonical encoding, so identical policy yields an identical version. */
        private fun versionOf(blocklists: List<SensitiveAreaBlocklist>): String {
            val content = blocklists.encodeToString()
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Renders [bundle] as the JSON body served at `GET /api/policy`.
 *
 * Hand-rolled (no JSON dependency) so the exact same codec is usable on the agent classpath, which
 * deliberately avoids a JSON library. [parsePolicyBundleJson] is the inverse for the fields the agent
 * needs: [PolicyBundle.version] and each blocklist's `encoded` form ([SensitiveAreaBlocklist.encodeToString]).
 * The human-readable `name` / `rules` fields are for operators inspecting the endpoint with `curl`.
 */
fun renderPolicyBundleJson(bundle: PolicyBundle): String {
    val blocklists = bundle.blocklists.joinToString(",") { blocklist ->
        val rules = blocklist.rules.joinToString(",") { jsonString(it.toString()) }
        "{" +
            "\"uuid\":${jsonString(blocklist.uuid.toString())}," +
            "\"name\":${jsonString(blocklist.name)}," +
            "\"rules\":[$rules]," +
            "\"encoded\":${jsonString(blocklist.encodeToString())}" +
            "}"
    }
    return "{\"version\":${jsonString(bundle.version)},\"blocklists\":[$blocklists]}"
}

/**
 * Parses a body produced by [renderPolicyBundleJson] back into a [PolicyBundle], reading each
 * blocklist from its machine-readable `encoded` field ([SensitiveAreaBlocklist.decodeFromString]).
 *
 * Only the `version` and per-blocklist `encoded` fields are consumed; any other/extra fields are
 * ignored. The `encoded` values never contain a quote or backslash, so the field regex is exact.
 */
fun parsePolicyBundleJson(json: String): PolicyBundle {
    val version = VERSION_REGEX.find(json)?.groupValues?.get(1).orEmpty()
    val blocklists = ENCODED_REGEX.findAll(json)
        .map { SensitiveAreaBlocklist.decodeFromString(it.groupValues[1]) }
        .toList()
    return PolicyBundle(version, blocklists)
}

private val VERSION_REGEX = Regex("\"version\"\\s*:\\s*\"([^\"]*)\"")
private val ENCODED_REGEX = Regex("\"encoded\"\\s*:\\s*\"([^\"]*)\"")

/** Minimal JSON string literal encoder (quotes + escapes control characters). */
private fun jsonString(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
