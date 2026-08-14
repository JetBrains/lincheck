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

import org.jetbrains.lincheck.jvm.agent.blocklist.BlocklistEngine
import org.jetbrains.lincheck.settings.BlocklistRule
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklistRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [BlocklistEngine] class-level and method-level decisions. */
class BlocklistEngineTest {

    private fun engine(vararg rules: BlocklistRule): BlocklistEngine {
        val registry = SensitiveAreaBlocklistRegistry()
        registry.add(listOf(SensitiveAreaBlocklist.of("test", rules.toList())))
        return BlocklistEngine(registry)
    }

    // ---- class-level ----

    @Test
    fun `package rule blocks the whole class`() {
        val e = engine(BlocklistRule.Package("com.corp.crypto"))
        assertNotNull(e.classBlock("com.corp.crypto.Aes"))
        assertNull(e.classBlock("com.other.Thing"))
    }

    @Test
    fun `class rule blocks the class and generated descendants`() {
        val e = engine(BlocklistRule.Class("com.corp.KeyStore"))
        assertNotNull(e.classBlock("com.corp.KeyStore"))
        assertNotNull(e.classBlock("com.corp.KeyStore\$1"))
        assertNull(e.classBlock("com.corp.KeyStoreImpl"))
    }

    @Test
    fun `method rule blocks its kotlinc nested body class at class level`() {
        val e = engine(BlocklistRule.Method("com.corp.Auth", "login"))
        assertNotNull(e.classBlock("com.corp.Auth\$login\$1"))
        assertNull(e.classBlock("com.corp.Auth"))
    }

    // ---- method-level ----

    @Test
    fun `method rule blocks the named method, all overloads by default`() {
        val e = engine(BlocklistRule.Method("com.corp.Auth", "login"))
        assertNotNull(e.methodBlock("com.corp.Auth", "login", "()V"))
        assertNotNull(e.methodBlock("com.corp.Auth", "login", "(Ljava/lang/String;)V"))
        assertNull(e.methodBlock("com.corp.Auth", "logout", "()V"))
        assertNull(e.methodBlock("com.corp.Other", "login", "()V"))
    }

    @Test
    fun `method rule with a descriptor pins one overload`() {
        val e = engine(BlocklistRule.Method("com.corp.Auth", "login", descriptor = "(Ljava/lang/String;)V"))
        assertNotNull(e.methodBlock("com.corp.Auth", "login", "(Ljava/lang/String;)V"))
        assertNull(e.methodBlock("com.corp.Auth", "login", "()V"))
    }

    @Test
    fun `method rule blocks javac lambda siblings`() {
        val e = engine(BlocklistRule.Method("com.corp.Auth", "login"))
        assertNotNull(e.methodBlock("com.corp.Auth", "lambda\$login\$0", "()V"))
        assertNull(e.methodBlock("com.corp.Auth", "lambda\$other\$0", "()V"))
    }

    @Test
    fun `descriptor-pinned rule matches every overload when the caller has no descriptor`() {
        // A stack frame carries no descriptor — the pinned rule must match fail-closed.
        val e = engine(BlocklistRule.Method("com.corp.Auth", "login", descriptor = "(Ljava/lang/String;)V"))
        assertNotNull(e.methodBlock("com.corp.Auth", "login", descriptor = null))
        assertNull(e.methodBlock("com.corp.Auth", "logout", descriptor = null))
    }

    @Test
    fun `empty engine blocks nothing`() {
        val e = BlocklistEngine(SensitiveAreaBlocklistRegistry())
        assertNull(e.classBlock("com.corp.Auth"))
        assertNull(e.methodBlock("com.corp.Auth", "login", "()V"))
    }

    @Test
    fun `classOwnsMethodRules classifies owners`() {
        val e = engine(BlocklistRule.Method("com.corp.Auth", "login"))
        assertTrue(e.classOwnsMethodRules("com.corp.Auth"))
        assertFalse(e.classOwnsMethodRules("com.corp.Other"))
    }
}
