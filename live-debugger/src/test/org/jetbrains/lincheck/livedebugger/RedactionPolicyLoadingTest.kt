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

import org.jetbrains.lincheck.jvm.agent.LincheckClassFileTransformer
import org.jetbrains.lincheck.settings.PolicyOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Fail-closed loading of the required startup redaction policy (`redactionFile=`). */
class RedactionPolicyLoadingTest {

    @Test
    fun `a configured but unreadable redaction file closes debugging until restart`() {
        val missingFile = File(
            System.getProperty("java.io.tmpdir"),
            "missing-redaction-${UUID.randomUUID()}.ini",
        )
        try {
            LiveDebugger.loadRedactionTemplatesFromFile(missingFile.absolutePath)

            assertFalse(LiveDebugger.isDebuggingAllowed)
            assertFalse(LincheckClassFileTransformer.liveDebuggerSettings.requiredRedactionPolicyValid)
        } finally {
            LiveDebugger.loadRedactionTemplatesFromFile(null)
        }
        assertTrue(LiveDebugger.isDebuggingAllowed)
    }

    @Test
    fun `a malformed redaction file closes debugging`() {
        val file = File.createTempFile("malformed-redaction", ".ini")
        try {
            file.writeText("[Redaction broken]\nunknownKey = value\n")
            LiveDebugger.loadRedactionTemplatesFromFile(file.absolutePath)

            assertFalse(LiveDebugger.isDebuggingAllowed)
        } finally {
            file.delete()
            LiveDebugger.loadRedactionTemplatesFromFile(null)
        }
    }

    @Test
    fun `a valid redaction file installs its templates and keeps debugging open`() {
        val file = File.createTempFile("redaction", ".ini")
        try {
            file.writeText(
                """
                    [Redaction Production defaults]
                    name = Production defaults
                    variable = *password*
                    valueRegex = \b(?:\d[ -]?){13,16}\b
                """.trimIndent(),
            )
            LiveDebugger.loadRedactionTemplatesFromFile(file.absolutePath)

            assertTrue(LiveDebugger.isDebuggingAllowed)
            val settings = LincheckClassFileTransformer.liveDebuggerSettings
            assertTrue(settings.requiredRedactionPolicyValid)
            val installed = settings.redactionRegistry.all()
            assertEquals(listOf("Production defaults"), installed.map { it.name })
            assertFalse(settings.redactionRegistry.snapshot().isEmpty)
        } finally {
            file.delete()
            LincheckClassFileTransformer.liveDebuggerSettings.redactionRegistry
                .replace(PolicyOwner.STARTUP_FILE, emptyList())
            LiveDebugger.loadRedactionTemplatesFromFile(null)
        }
    }
}
