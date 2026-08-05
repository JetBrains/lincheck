/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.network

import org.jetbrains.lincheck.settings.BlocklistRule
import org.jetbrains.lincheck.settings.BreakpointExpressionSlot
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.settings.decodeBlocklistsFromString
import org.jetbrains.lincheck.settings.encodeToString
import org.jetbrains.lincheck.trace.network.websocket.handleMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Round-trips the new blocklist command and notification through their WebSocket wire encoding. */
class BlocklistWireProtocolTest {

    private class RecordingCommands : TracingCommands {
        var addCalls = 0
        var blocklists: List<SensitiveAreaBlocklist> = emptyList()
        override fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean) {}
        override fun startNetworkTracing() {}
        override fun stopTracing() {}
        override fun addBreakpoints(breakpoints: List<SnapshotBreakpoint>) {}
        override fun removeBreakpoints(uuids: List<UUID>) {}
        override fun addSensitiveAreaBlocklists(blocklists: List<SensitiveAreaBlocklist>) {
            addCalls++
            this.blocklists = blocklists
        }
    }

    private class RecordingCallbacks : TracingCallbacks {
        var blockedData: LiveDebuggerNotification.BreakpointData? = null
        var reason: String? = null
        var suppressedData: LiveDebuggerNotification.BreakpointData? = null
        var suppressedFrameClass: String? = null
        var suppressedReason: String? = null
        override fun hitLimitReached(breakpointData: LiveDebuggerNotification.BreakpointData, timestamp: Long) {}
        override fun breakpointExpressionUnsafe(
            breakpointData: LiveDebuggerNotification.BreakpointData,
            slot: BreakpointExpressionSlot,
            safetyViolationMessage: String,
            timestamp: Long,
        ) {}
        override fun breakpointBlocked(
            breakpointData: LiveDebuggerNotification.BreakpointData,
            reason: String,
            timestamp: Long,
        ) {
            this.blockedData = breakpointData
            this.reason = reason
        }
        override fun breakpointHitSuppressed(
            breakpointData: LiveDebuggerNotification.BreakpointData,
            blockedFrameClass: String,
            reason: String,
            timestamp: Long,
        ) {
            this.suppressedData = breakpointData
            this.suppressedFrameClass = blockedFrameClass
            this.suppressedReason = reason
        }
        override fun binaryTraceData(data: ByteArray) {}
        override fun close() {}
    }

    @Test
    fun `addSensitiveAreaBlocklists round-trips through the wire dispatch`() {
        val blocklists = listOf(
            SensitiveAreaBlocklist.of(
                "Crypto & key handling",
                listOf(
                    BlocklistRule.Package("com.corp.crypto"),
                    // A descriptor carries ';' and '/', stressing the separator handling.
                    BlocklistRule.Method("com.corp.Auth", "login", descriptor = "(Ljava/lang/String;)V"),
                ),
            ),
        )
        val message = "${TracingCommands.ADD_SENSITIVE_AREA_BLOCKLISTS}:${blocklists.encodeToString()}"

        val recorder = RecordingCommands()
        recorder.handleMessage(message)

        assertEquals(1, recorder.addCalls)
        assertEquals(1, recorder.blocklists.size)
        assertEquals(blocklists[0].uuid, recorder.blocklists[0].uuid)
        assertEquals(blocklists[0].rules, recorder.blocklists[0].rules)
    }

    @Test
    fun `empty blocklist payload dispatches an empty addition`() {
        val recorder = RecordingCommands()
        recorder.handleMessage("${TracingCommands.ADD_SENSITIVE_AREA_BLOCKLISTS}:")
        assertEquals(1, recorder.addCalls)
        assertEquals(0, recorder.blocklists.size)
    }

    @Test
    fun `breakpointBlocked round-trips through the wire dispatch, reason with separators preserved`() {
        val data = LiveDebuggerNotification.BreakpointData(
            breakpointUuid = UUID.randomUUID(),
            className = "com.corp.crypto.Aes",
            fileName = "Aes.java",
            lineNumber = 42,
        )
        val reason = "blocked by blocklist \"Crypto\", rule Method(com.corp.Auth#login(Ljava/lang/String;)V)"
        val message = "${TracingCallbacks.BREAKPOINT_BLOCKED}:12345:$data;$reason"

        val recorder = RecordingCallbacks()
        recorder.handleMessage(message)

        assertEquals(data, recorder.blockedData)
        assertEquals(reason, recorder.reason)
    }

    @Test
    fun `breakpointHitSuppressed round-trips through the wire dispatch, reason with separators preserved`() {
        val data = LiveDebuggerNotification.BreakpointData(
            breakpointUuid = UUID.randomUUID(),
            className = "com.corp.shared.Validator",
            fileName = "Validator.java",
            lineNumber = 7,
        )
        val frameClass = "com.corp.crypto.KeyStore"
        val reason = "blocked by blocklist \"Crypto\"; rule Class(com.corp.crypto.KeyStore)"
        val message = "${TracingCallbacks.BREAKPOINT_HIT_SUPPRESSED}:12345:$data;$frameClass;$reason"

        val recorder = RecordingCallbacks()
        recorder.handleMessage(message)

        assertEquals(data, recorder.suppressedData)
        assertEquals(frameClass, recorder.suppressedFrameClass)
        assertEquals(reason, recorder.suppressedReason)
    }

    @Test
    fun `rule decoding tolerates extra trailing fields from future writers`() {
        // A future writer may append rule options; an older reader must still enforce the rule.
        val cls = BlocklistRule.decodeFromString("C:com.corp.KeyStore:futureFlag")
        assertEquals(BlocklistRule.Class("com.corp.KeyStore"), cls)

        val method = BlocklistRule.decodeFromString("M:com.corp.Auth:login::futureFlag")
        assertEquals(BlocklistRule.Method("com.corp.Auth", "login"), method)

        val pkg = BlocklistRule.decodeFromString("P:com.corp.crypto:futureFlag")
        assertEquals(BlocklistRule.Package("com.corp.crypto"), pkg)
    }

    @Test
    fun `one undecodable blocklist line is skipped, the rest still apply`() {
        val good = SensitiveAreaBlocklist.of("Crypto", listOf(BlocklistRule.Package("com.corp.crypto")))
        val payload = "not-a-blocklist-line\n${listOf(good).encodeToString()}"

        val decoded = decodeBlocklistsFromString(payload)

        assertEquals(1, decoded.size)
        assertEquals(good.uuid, decoded[0].uuid)
    }
}
