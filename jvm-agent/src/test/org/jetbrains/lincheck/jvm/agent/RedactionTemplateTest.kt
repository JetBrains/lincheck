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

import org.jetbrains.lincheck.settings.CompiledRedactionPolicy
import org.jetbrains.lincheck.settings.PolicyOwner
import org.jetbrains.lincheck.settings.RedactionFileParser
import org.jetbrains.lincheck.settings.RedactionRule
import org.jetbrains.lincheck.settings.RedactionTemplate
import org.jetbrains.lincheck.settings.RedactionTemplateRegistry
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class RedactionTemplateTest {
    @Test
    fun `canonical INI round-trips ordered repeated rules and punctuation`() {
        val template = RedactionTemplate.of(
            "GDPR defaults #1",
            listOf(
                RedactionRule.ByName("*password*"),
                RedactionRule.ByName("card#suffix", className = "com.shop.Payment=Details"),
                RedactionRule.ByName("email", packageName = "com.shop.customers"),
                RedactionRule.ByValue("""\b(?:\d[ -]?){13,16}\b[#;=,|]?"""),
            ),
        )

        val rendered = RedactionFileParser.renderTemplates(listOf(template))
        val decoded = RedactionFileParser.parseTemplates(rendered).single()

        assertEquals(template.uuid, decoded.uuid)
        assertEquals(template.name, decoded.name)
        assertEquals(template.rules, decoded.rules)
    }

    @Test
    fun `redaction file rejects malformed UTF-8`() {
        val malformedIni = (
            """
                [Redaction 1]
                name = malformed
                valueRegex = secret
            """.trimIndent().toByteArray(Charsets.UTF_8) + byteArrayOf(0xC3.toByte())
        )
        val file = File.createTempFile("malformed-redaction", ".ini")
        try {
            file.writeBytes(malformedIni)
            assertThrows(IllegalArgumentException::class.java) {
                RedactionFileParser.parseTemplatesFile(file.absolutePath)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `configured UUID must equal deterministic content UUID`() {
        val invalid = """
            [Redaction 1]
            name = GDPR defaults
            uuid = 550e8400-e29b-41d4-a716-446655440000
            variable = *password*
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            RedactionFileParser.parseTemplates(invalid)
        }
    }

    @Test
    fun `unsupported backtracking regex is rejected by RE2J`() {
        assertThrows(IllegalArgumentException::class.java) {
            RedactionFileParser.parseTemplates(
                """
                    [Redaction unsupported]
                    valueRegex = (secret)\1
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `unknown duplicate metadata and malformed scope reject the complete file`() {
        val validPrefix = """
            [Redaction valid]
            variable = password

        """.trimIndent()
        val invalidSections = listOf(
            """
                [Redaction unknown]
                unknown = value
            """.trimIndent(),
            """
                [Redaction duplicate]
                name = first
                name = second
            """.trimIndent(),
            """
                [Redaction malformed]
                classVariable = missing-separator
            """.trimIndent(),
        )

        invalidSections.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                RedactionFileParser.parseTemplates("$validPrefix\n\n$invalid")
            }
        }
    }

    @Test
    fun `template names are bounded single-line policy metadata`() {
        assertThrows(IllegalArgumentException::class.java) {
            RedactionTemplate.of("line one\nline two", emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            RedactionTemplate.of("x".repeat(RedactionTemplate.MAX_NAME_LENGTH + 1), emptyList())
        }
        assertEquals(
            RedactionTemplate.MAX_NAME_LENGTH,
            RedactionTemplate.of("x".repeat(RedactionTemplate.MAX_NAME_LENGTH), emptyList()).name.length,
        )
    }

    @Test
    fun `value matching uses find semantics over the supplied bounded representation`() {
        val policy = RedactionTemplateRegistry().apply {
            replace(
                PolicyOwner.STARTUP_FILE,
                listOf(RedactionTemplate.of("values", listOf(RedactionRule.ByValue("""secret-\d+""")))),
            )
        }.snapshot()

        assertNotNull(policy.matchValue("prefix secret-42 suffix"))
        assertNull(policy.matchValue(("x".repeat(50) + "secret-42").take(50)))
    }

    @Test
    fun `template order supplies first-match attribution while rule order round-trips`() {
        val first = RedactionTemplate.of(
            "first",
            listOf(RedactionRule.ByName("password"), RedactionRule.ByValue("secret")),
        )
        val second = RedactionTemplate.of("second", listOf(RedactionRule.ByValue("secret")))
        val registry = RedactionTemplateRegistry()

        registry.replace(PolicyOwner.STARTUP_FILE, listOf(second, first))

        assertEquals("second", registry.snapshot().matchValue("secret")?.templateName)
        assertEquals(listOf(second, first), RedactionFileParser.parseTemplates(
            RedactionFileParser.renderTemplates(listOf(second, first)),
        ))
        assertEquals(first.rules, RedactionFileParser.parseTemplates(
            RedactionFileParser.renderTemplates(listOf(first)),
        ).single().rules)
    }

    @Test
    fun `failed replacement retains the previously compiled owner policy`() {
        val registry = RedactionTemplateRegistry()
        val previous = RedactionTemplate.of("previous", listOf(RedactionRule.ByValue("old-secret")))
        val invalid = RedactionTemplate.of("invalid", listOf(RedactionRule.ByValue("""(secret)\1""")))
        registry.replace(PolicyOwner.STARTUP_FILE, listOf(previous))

        assertThrows(IllegalArgumentException::class.java) {
            registry.replace(PolicyOwner.STARTUP_FILE, listOf(invalid))
        }

        assertEquals("previous", registry.snapshot().matchValue("old-secret")?.templateName)
        assertNull(registry.snapshot().matchValue("secret"))
        assertEquals(listOf(previous), registry.all())
    }

    @Test
    fun `valid empty policy uses the shared empty compiled state`() {
        val registry = RedactionTemplateRegistry()
        val emptyTemplate = RedactionTemplate.of("empty", emptyList())

        assertEquals(emptyList<RedactionTemplate>(), RedactionFileParser.parseTemplates(""))
        registry.replace(PolicyOwner.STARTUP_FILE, listOf(emptyTemplate))

        assertSame(CompiledRedactionPolicy.EMPTY, registry.snapshot())
        assertTrue(registry.snapshot().isEmpty)
    }

    @Test
    fun `name matching is case insensitive and scope boundaries are respected`() {
        val template = RedactionTemplate.of(
            "names",
            listOf(
                RedactionRule.ByName("*PASSWORD*"),
                RedactionRule.ByName("apiKey", className = "Auth"),
                RedactionRule.ByName("token", className = "com.shop.Auth"),
                RedactionRule.ByName("email", packageName = "com.shop.customers"),
            ),
        )
        val policy = RedactionTemplateRegistry().apply {
            replace(PolicyOwner.STARTUP_FILE, listOf(template))
        }.snapshot()

        assertNotNull(policy.matchName("dbPasswordHash", "example.Unrelated"))
        assertNotNull(policy.matchName("apiKey", "com.shop.Auth"))
        assertNotNull(policy.matchName("apiKey", "example.Auth"))
        assertNull(policy.matchName("apiKey", "com.shop.Auth\$Request"))
        assertNull(policy.matchName("apiKey", "com.shop.Authenticator"))
        assertNotNull(policy.matchName("token", "com.shop.Auth"))
        assertNotNull(policy.matchName("token", "com.shop.Auth\$Request"))
        assertNull(policy.matchName("token", "com.shop.Authenticator"))
        assertNotNull(policy.matchName("email", "com.shop.customers.internal.User"))
        assertNull(policy.matchName("email", "com.shop.customersx.User"))
    }

    @Test
    fun `startup and control-plane policies combine by union and replace independently`() {
        val startup = RedactionTemplate.of("startup", listOf(RedactionRule.ByName("password")))
        val first = RedactionTemplate.of("first control plane", listOf(RedactionRule.ByValue("first-secret")))
        val second = RedactionTemplate.of("second control plane", listOf(RedactionRule.ByValue("second-secret")))
        val registry = RedactionTemplateRegistry()

        registry.replace(PolicyOwner.STARTUP_FILE, listOf(startup))
        registry.replace(PolicyOwner.CONTROL_PLANE, listOf(first))
        assertNotNull(registry.snapshot().matchValue("first-secret"))

        registry.replace(PolicyOwner.CONTROL_PLANE, listOf(second))

        val policy = registry.snapshot()
        assertNotNull(policy.matchName("password", "example.User"))
        assertNull(policy.matchValue("first-secret"))
        assertEquals("second control plane", policy.matchValue("second-secret")?.templateName)

        registry.replace(PolicyOwner.CONTROL_PLANE, emptyList())
        assertNotNull(registry.snapshot().matchName("password", "example.User"))
        assertNull(registry.snapshot().matchValue("second-secret"))
    }

    @Test
    fun `watch label codec preserves full expressions`() {
        val labels = listOf(
            "customer.password",
            "map[\"a:b,c\"]",
            "line1\nline2",
            "",
        )
        val encoded = SnapshotBreakpoint.encodeWatchLabels(labels)

        assertTrue(encoded != "null")
        assertEquals(labels, SnapshotBreakpoint.decodeWatchLabels(encoded))
        assertNull(SnapshotBreakpoint.decodeWatchLabels("null"))
    }

    @Test
    fun `snapshot breakpoint appends watch labels while decoding legacy payloads`() {
        val breakpoint = SnapshotBreakpoint(
            uuid = UUID.randomUUID(),
            className = "example.App",
            fileName = "App.kt",
            lineNumber = 42,
            watchLabels = listOf("customer.password", "map[\"a:b,c\"]"),
        )

        val encoded = breakpoint.encodeToString()
        assertEquals(breakpoint.watchLabels, SnapshotBreakpoint.decodeFromString(encoded).watchLabels)
        // A payload from before the field existed carries no metadata at all. Decoding it to an empty
        // list would make capture reject the hit for a slot-count mismatch that is not one.
        assertNull(SnapshotBreakpoint.decodeFromString(encoded.substringBeforeLast(":")).watchLabels)
    }
}
