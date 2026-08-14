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

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.jetbrains.lincheck.settings.BlocklistRule
import org.jetbrains.lincheck.settings.PolicyBundle
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.renderPolicyBundleJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress

/**
 * The startup policy pull (`policyBootstrap=controlPlane`) against an agent-secret-protected control
 * plane: `GET /api/policy` sits behind the same provider as the heartbeat, so it has to present the
 * credential too.
 */
class ControlPlanePolicyPullTest {

    private val blocklist = SensitiveAreaBlocklist.of(
        "Crypto & key handling",
        listOf(BlocklistRule.Package("com.corp.crypto")),
    )

    @Test
    fun `the policy pull presents the agent secret`() {
        val secret = "s3cr3t-agent-key"
        val received = mutableListOf<String?>()

        val blocklists = withPolicyServer(
            received,
            status = { exchange -> if (exchange.requestHeaders.getFirst(AGENT_SECRET_HEADER) == secret) 200 else 401 },
        ) { url ->
            ControlPlane.pullPolicyBlocklists(url, mapOf(AGENT_SECRET_HEADER to secret))
        }

        assertEquals(listOf(secret), received)
        assertEquals(listOf(blocklist.uuid), blocklists?.map { it.uuid })
    }

    @Test
    fun `an unprotected control plane is pulled without a credential`() {
        val received = mutableListOf<String?>()

        val blocklists = withPolicyServer(received, status = { 200 }) { url ->
            ControlPlane.pullPolicyBlocklists(url, emptyMap())
        }

        assertEquals(listOf(null), received)
        assertEquals(listOf(blocklist.uuid), blocklists?.map { it.uuid })
    }

    @Test
    fun `a rejected policy pull yields no policy`() {
        val received = mutableListOf<String?>()

        val blocklists = withPolicyServer(received, status = { 401 }) { url ->
            ControlPlane.pullPolicyBlocklists(url, emptyMap())
        }

        assertNull(blocklists)
        // Every attempt of the retry budget is spent before giving up.
        assertEquals(3, received.size)
    }

    /**
     * Serves the test bundle on an ephemeral port, recording the agent-secret header of every request
     * into [received], and runs [pull] against the resulting `/api/policy` URL.
     */
    private fun <T> withPolicyServer(
        received: MutableList<String?>,
        status: (HttpExchange) -> Int,
        pull: (url: String) -> T,
    ): T {
        val body = renderPolicyBundleJson(PolicyBundle.of(listOf(blocklist))).toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/policy") { exchange ->
            received += exchange.requestHeaders.getFirst(AGENT_SECRET_HEADER)
            val code = status(exchange)
            if (code == 200) {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                exchange.sendResponseHeaders(code, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            return pull("http://127.0.0.1:${server.address.port}/api/policy")
        } finally {
            server.stop(0)
        }
    }
}
