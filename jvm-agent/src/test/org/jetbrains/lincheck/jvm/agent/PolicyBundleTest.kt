/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.jetbrains.lincheck.settings.BlocklistRule
import org.jetbrains.lincheck.settings.PolicyBundle
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.parsePolicyBundleJson
import org.jetbrains.lincheck.settings.renderPolicyBundleJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the [PolicyBundle] JSON codec shared by the control plane (renders `GET /api/policy`)
 * and the agent (pulls it at startup via `policyBootstrap=controlPlane`).
 */
class PolicyBundleTest {

    private val crypto = SensitiveAreaBlocklist.of(
        "Crypto & key handling",
        listOf(
            BlocklistRule.Package("com.corp.crypto"),
            BlocklistRule.Class("com.corp.security.KeyStore"),
        ),
    )
    private val payments = SensitiveAreaBlocklist.of(
        "Payments",
        listOf(BlocklistRule.Method("com.corp.payments.CardProcessor", "charge", descriptor = null)),
    )

    @Test
    fun `render then parse round-trips the version and every blocklist`() {
        val bundle = PolicyBundle.of(listOf(crypto, payments))

        val parsed = parsePolicyBundleJson(renderPolicyBundleJson(bundle))

        assertEquals(bundle.version, parsed.version)
        assertEquals(listOf(crypto.uuid, payments.uuid), parsed.blocklists.map { it.uuid })
        assertEquals(
            crypto.rules.map { it.encodeToString() },
            parsed.blocklists[0].rules.map { it.encodeToString() },
        )
        assertEquals(
            payments.rules.map { it.encodeToString() },
            parsed.blocklists[1].rules.map { it.encodeToString() },
        )
    }

    @Test
    fun `empty bundle round-trips to no blocklists`() {
        val bundle = PolicyBundle.of(emptyList())

        val parsed = parsePolicyBundleJson(renderPolicyBundleJson(bundle))

        assertEquals(bundle.version, parsed.version)
        assertTrue(parsed.blocklists.isEmpty())
    }

    @Test
    fun `version is deterministic for identical content and differs for different content`() {
        assertEquals(
            PolicyBundle.of(listOf(crypto, payments)).version,
            PolicyBundle.of(listOf(crypto, payments)).version,
        )
        assertNotEquals(
            PolicyBundle.of(listOf(crypto)).version,
            PolicyBundle.of(listOf(crypto, payments)).version,
        )
    }

    @Test
    fun `rendered json exposes readable name and rule fields for operators`() {
        val json = renderPolicyBundleJson(PolicyBundle.of(listOf(crypto)))

        assertTrue(json.contains("\"name\":\"Crypto & key handling\""))
        assertTrue(json.contains("Package(com.corp.crypto)"))
        assertTrue(json.contains("\"encoded\":"))
    }

    @Test
    fun `parse reads a fixed control-plane response shape and tolerates extra fields`() {
        val json = """
            {"version":"abc123","blocklists":[
              {"uuid":"${crypto.uuid}","name":"Crypto & key handling",
               "rules":["Package(com.corp.crypto)"],"encoded":"${crypto.encodeToString()}"}
            ]}
        """.trimIndent()

        val parsed = parsePolicyBundleJson(json)

        assertEquals("abc123", parsed.version)
        assertEquals(1, parsed.blocklists.size)
        assertEquals(crypto.uuid, parsed.blocklists.single().uuid)
    }
}
