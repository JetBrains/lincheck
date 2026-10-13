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

import org.jetbrains.lincheck.settings.liveDebuggerSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The control-plane policy source (`policySource=controlPlane`): delivered on heartbeats, fail-closed until then. */
class ControlPlanePolicyTest {
    private val redaction = "[Redaction Secrets]\nname = Secrets\nvariable = *password*\n"
    private val blocklists = "[Blocklist Payments]\nname = Payments\npackage = com.corp.payments\n"

    @After
    fun restore() {
        LiveDebugger.applyControlPlanePolicy(ControlPlanePolicy("empty", "", ""))
    }

    @Test
    fun `debugging stays closed until the control plane's policy is applied`() {
        LiveDebugger.awaitControlPlanePolicy(deploymentFilesConfigured = false, heartbeatEnabled = true)
        assertFalse(LiveDebugger.isDebuggingAllowed)

        LiveDebugger.applyControlPlanePolicy(ControlPlanePolicy("v1", redaction, blocklists))

        assertTrue(LiveDebugger.isDebuggingAllowed)
        assertEquals("v1", LiveDebugger.controlPlanePolicyVersion)
        assertEquals(listOf("Secrets"), liveDebuggerSettings.redactionRegistry.all().map { it.name })
        assertEquals(listOf("Payments"), liveDebuggerSettings.blocklistRegistry.all().map { it.name })
    }

    @Test
    fun `a later policy replaces the previous one, blocklists included`() {
        LiveDebugger.applyControlPlanePolicy(ControlPlanePolicy("v1", redaction, blocklists))
        LiveDebugger.applyControlPlanePolicy(ControlPlanePolicy("v2", "", ""))

        assertEquals("v2", LiveDebugger.controlPlanePolicyVersion)
        assertTrue(liveDebuggerSettings.redactionRegistry.all().isEmpty())
        assertTrue(liveDebuggerSettings.blocklistRegistry.all().isEmpty())
    }

    @Test
    fun `a policy that does not parse closes debugging`() {
        LiveDebugger.applyControlPlanePolicy(ControlPlanePolicy("v1", redaction, blocklists))
        LiveDebugger.applyControlPlanePolicy(ControlPlanePolicy("broken", "[Redaction X]\nbogus = 1\n", ""))

        assertFalse(LiveDebugger.isDebuggingAllowed)
        assertNull(LiveDebugger.controlPlanePolicyVersion)
    }

    @Test
    fun `losing the control plane closes debugging until its policy is delivered again`() {
        LiveDebugger.applyControlPlanePolicy(ControlPlanePolicy("v1", redaction, blocklists))
        assertFalse(PhoneHomeHeartbeat.isControlPlaneLost(CONTROL_PLANE_LOSS_TIMEOUT_MS - 1))
        assertTrue(PhoneHomeHeartbeat.isControlPlaneLost(CONTROL_PLANE_LOSS_TIMEOUT_MS))

        LiveDebugger.revokeControlPlanePolicy()

        assertFalse(LiveDebugger.isDebuggingAllowed)
        assertNull("the next heartbeat asks for the policy again", LiveDebugger.controlPlanePolicyVersion)
        LiveDebugger.applyControlPlanePolicy(ControlPlanePolicy("v1", redaction, blocklists))
        assertTrue(LiveDebugger.isDebuggingAllowed)
    }

    @Test
    fun `heartbeats report the version in force and decode the policy sent back`() {
        assertEquals(
            """{"name":"pod","namespace":"ns","serverPort":9999,"policySource":"controlPlane","policyVersion":"v1"}""",
            PhoneHomeHeartbeat.requestBody("pod", "ns", 9999, policyFromControlPlane = true, policyVersion = "v1"),
        )
        assertEquals(
            """{"name":"pod","namespace":"ns","serverPort":9999,"policySource":"deployment"}""",
            PhoneHomeHeartbeat.requestBody("pod", "ns", 9999, policyFromControlPlane = false, policyVersion = null),
        )

        val encoder = Base64.getEncoder()
        val response = PhoneHomeHeartbeat.parseHeartbeatResponse(
            """{"connect":true,"agentId":"a","policyVersion":"v2",""" +
                """"redactionIniBase64":"${encoder.encodeToString(redaction.toByteArray())}","blocklistsIniBase64":""}""",
        )
        assertEquals(ControlPlanePolicy("v2", redaction, ""), response?.policy)
        assertNull(PhoneHomeHeartbeat.parseHeartbeatResponse("""{"connect":false,"agentId":"a"}""")?.policy)
    }

    @Test
    fun `only a well-formed answer counts as one, so anything else runs down the loss timeout`() {
        val malformed = listOf(
            "{}",
            "<html><body>502 Bad Gateway</body></html>",
            """{"connect":true}""",
            """{"connect":true,"agentId":""}""",
            """{"connect":false,"agentId":"a","policyVersion":"v2"}""",
            """{"connect":false,"agentId":"a","redactionIniBase64":"","blocklistsIniBase64":""}""",
            """{"connect":false,"agentId":"a","policyVersion":"v2","redactionIniBase64":"%%","blocklistsIniBase64":""}""",
        )
        for (body in malformed) assertNull(body, PhoneHomeHeartbeat.parseHeartbeatResponse(body))
    }
}
