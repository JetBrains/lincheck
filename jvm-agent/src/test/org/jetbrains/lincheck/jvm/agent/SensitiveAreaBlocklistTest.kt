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

import org.jetbrains.lincheck.settings.BlocklistFileParser
import org.jetbrains.lincheck.settings.BlocklistRule
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklistRegistry
import org.jetbrains.lincheck.settings.decodeBlocklistsFromString
import org.jetbrains.lincheck.settings.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Pure-logic tests for the [SensitiveAreaBlocklist] data model: matching, encoding, INI parsing, registry. */
class SensitiveAreaBlocklistTest {

    // ---- Package rules: segment-aware prefix ----

    @Test
    fun `package rule blocks the package and its subpackages, not lookalikes`() {
        val rule = BlocklistRule.Package("com.corp.crypto")
        assertTrue(rule.matchesClassStatically("com.corp.crypto.Aes"))
        assertTrue(rule.matchesClassStatically("com.corp.crypto.aes.Cipher"))
        assertFalse(rule.matchesClassStatically("com.corp.cryptox.Y"))
        assertFalse(rule.matchesClassStatically("com.corp.Crypto"))
    }

    // ---- Class rules: name + generated descendants ----

    @Test
    fun `class rule blocks the class and its generated descendants but not siblings`() {
        val rule = BlocklistRule.Class("com.corp.KeyStore")
        assertTrue(rule.matchesClassStatically("com.corp.KeyStore"))
        assertTrue(rule.matchesClassStatically("com.corp.KeyStore\$1"))
        assertTrue(rule.matchesClassStatically("com.corp.KeyStore\$load\$1"))
        assertFalse(rule.matchesClassStatically("com.corp.KeyStoreImpl"))
        assertFalse(rule.matchesClassStatically("com.corp.OtherKeyStore"))
    }

    // ---- Method rules: only kotlinc nested bodies are statically class-decidable ----

    @Test
    fun `method rule statically matches only its kotlinc nested method-body class`() {
        val rule = BlocklistRule.Method("com.corp.Auth", "login")
        assertTrue(rule.matchesClassStatically("com.corp.Auth\$login\$1"))
        // The owning class itself is not wholly blocked by a method rule (only the method is).
        assertFalse(rule.matchesClassStatically("com.corp.Auth"))
        assertFalse(rule.matchesClassStatically("com.corp.Auth\$other\$1"))
    }

    // ---- Encoding round-trips ----

    @Test
    fun `rules round-trip through encode-decode`() {
        val rules = listOf(
            BlocklistRule.Package("com.corp.crypto"),
            BlocklistRule.Class("com.corp.KeyStore"),
            BlocklistRule.Method("com.corp.Auth", "login", descriptor = "(Ljava/lang/String;)V"),
            BlocklistRule.Method("com.corp.Auth", "logout"),
        )
        for (rule in rules) {
            assertEquals(rule, BlocklistRule.decodeFromString(rule.encodeToString()))
        }
    }

    @Test
    fun `blocklist round-trips including a name with separators`() {
        val blocklist = SensitiveAreaBlocklist.of(
            name = "Crypto & key handling: v1, strict",
            rules = listOf(
                BlocklistRule.Package("com.corp.crypto"),
                BlocklistRule.Method("com.corp.Auth", "login", descriptor = "(Ljava/lang/String;)V"),
            ),
        )
        val decoded = SensitiveAreaBlocklist.decodeFromString(blocklist.encodeToString())
        assertEquals(blocklist.uuid, decoded.uuid)
        assertEquals(blocklist.name, decoded.name)
        assertEquals(blocklist.rules, decoded.rules)
    }

    @Test
    fun `blocklist list round-trips, empty rule list included`() {
        val list = listOf(
            SensitiveAreaBlocklist.of("A", listOf(BlocklistRule.Package("com.a"))),
            SensitiveAreaBlocklist.of("B (empty)", emptyList()),
        )
        val decoded = decodeBlocklistsFromString(list.encodeToString())
        assertEquals(2, decoded.size)
        assertEquals(list[0].rules, decoded[0].rules)
        assertTrue(decoded[1].rules.isEmpty())
    }

    @Test
    fun `content-identity uuid is deterministic for identical content and differs otherwise`() {
        val a = SensitiveAreaBlocklist.of("Crypto", listOf(BlocklistRule.Package("com.a")))
        val b = SensitiveAreaBlocklist.of("Crypto", listOf(BlocklistRule.Package("com.a")))
        val c = SensitiveAreaBlocklist.of("Crypto", listOf(BlocklistRule.Package("com.b")))
        assertEquals(a.uuid, b.uuid)
        assertFalse(a.uuid == c.uuid)
    }

    // ---- INI parser ----

    @Test
    fun `INI parser reads all rule kinds with repeated keys`() {
        val ini = """
            # comment
            [Blocklist Crypto]
            name = Crypto & key handling
            package = com.corp.crypto
            class = com.corp.KeyStore
            method = com.corp.Auth#login
            method = com.corp.Auth#verify:(Ljava/lang/String;)V
        """.trimIndent()

        val blocklists = BlocklistFileParser.parseBlocklists(ini)
        assertEquals(1, blocklists.size)
        val bl = blocklists.single()
        assertEquals("Crypto & key handling", bl.name)
        assertEquals(4, bl.rules.size)

        assertTrue(bl.rules.contains(BlocklistRule.Package("com.corp.crypto")))
        assertTrue(bl.rules.contains(BlocklistRule.Class("com.corp.KeyStore")))
        assertTrue(bl.rules.contains(BlocklistRule.Method("com.corp.Auth", "login")))
        assertTrue(
            bl.rules.contains(
                BlocklistRule.Method("com.corp.Auth", "verify", descriptor = "(Ljava/lang/String;)V")
            )
        )
    }

    @Test
    fun `INI parser defaults the name to the section id`() {
        val ini = """
            [Blocklist Payments]
            package = com.corp.payments
        """.trimIndent()
        assertEquals("Payments", BlocklistFileParser.parseBlocklists(ini).single().name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `INI parser rejects an unknown property`() {
        BlocklistFileParser.parseBlocklists("[Blocklist X]\nbogus = value")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `INI parser rejects a bad section header`() {
        BlocklistFileParser.parseBlocklists("[NotABlocklist]\npackage = com.a")
    }

    // ---- Registry: add-only union, idempotent re-add, static match ----

    @Test
    fun `registry unions rules across additions and re-adding is idempotent`() {
        val registry = SensitiveAreaBlocklistRegistry()
        assertTrue(registry.isEmpty())

        val fromFile = SensitiveAreaBlocklist.of("file", listOf(BlocklistRule.Package("com.a")))
        val fromControlPlane = SensitiveAreaBlocklist.of("cp", listOf(BlocklistRule.Package("com.b")))
        registry.add(listOf(fromFile))
        registry.add(listOf(fromControlPlane))

        assertFalse(registry.isEmpty())
        assertEquals(2, registry.allRules().size)
        assertNotNull(registry.staticBlockMatch("com.a.Foo"))
        assertNotNull(registry.staticBlockMatch("com.b.Foo"))
        assertNull(registry.staticBlockMatch("com.c.Foo"))

        // Re-pushing the same content (same content-identity uuid) changes nothing.
        registry.add(listOf(fromFile, fromControlPlane))
        assertEquals(2, registry.all().size)
        assertEquals(2, registry.allRules().size)
    }

    @Test
    fun `registry keeps the first registration when a same-uuid blocklist arrives with different content`() {
        // A sender violating the content-identity contract (same uuid, edited rules)
        // must not silently override — the first registration wins deterministically.
        val uuid = UUID.randomUUID()
        val registry = SensitiveAreaBlocklistRegistry()
        registry.add(listOf(SensitiveAreaBlocklist(uuid, "A", listOf(BlocklistRule.Package("com.a")))))
        registry.add(listOf(SensitiveAreaBlocklist(uuid, "A", listOf(BlocklistRule.Package("com.b")))))

        assertEquals(listOf<BlocklistRule>(BlocklistRule.Package("com.a")), registry.all().single().rules)
    }

    @Test
    fun `static block match reports the owning blocklist name`() {
        val registry = SensitiveAreaBlocklistRegistry()
        registry.add(listOf(SensitiveAreaBlocklist.of("Crypto policy", listOf(BlocklistRule.Package("com.corp.crypto")))))
        val match = registry.staticBlockMatch("com.corp.crypto.Aes")
        assertNotNull(match)
        assertEquals("Crypto policy", match!!.blocklistName)
    }
}
