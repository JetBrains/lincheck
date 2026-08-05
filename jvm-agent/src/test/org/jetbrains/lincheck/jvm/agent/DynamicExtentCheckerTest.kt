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
import org.jetbrains.lincheck.jvm.agent.blocklist.DynamicExtentChecker
import org.jetbrains.lincheck.settings.BlocklistRule
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklistRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for [DynamicExtentChecker] frame scanning, memoization, and invalidation. */
class DynamicExtentCheckerTest {

    private val blocked = "com.corp.crypto.Aes"
    private val unrelated = "com.corp.app.Service"

    private fun frame(className: String, methodName: String = "run"): StackTraceElement =
        StackTraceElement(className, methodName, "F.java", 1)

    private fun registryOf(vararg rules: BlocklistRule) = SensitiveAreaBlocklistRegistry().apply {
        add(listOf(SensitiveAreaBlocklist.of("test", rules.toList())))
    }

    @Test
    fun `finds the first blocked frame in a mixed stack`() {
        val checker = DynamicExtentChecker(BlocklistEngine(registryOf(BlocklistRule.Class(blocked))))
        val stack = listOf(frame(unrelated), frame(blocked), frame("com.other.Thing"))
        val blockedFrame = checker.firstBlockedFrame(stack)
        assertNotNull(blockedFrame)
        assertEquals(blocked, blockedFrame!!.frame.className)
    }

    @Test
    fun `clear stack yields no blocked frame`() {
        val checker = DynamicExtentChecker(BlocklistEngine(registryOf(BlocklistRule.Class(blocked))))
        assertNull(checker.firstBlockedFrame(listOf(frame(unrelated), frame("com.other.Thing"))))
    }

    @Test
    fun `empty policy short-circuits`() {
        val checker = DynamicExtentChecker(BlocklistEngine(SensitiveAreaBlocklistRegistry()))
        assertNull(checker.firstBlockedFrame(listOf(frame(blocked))))
    }

    @Test
    fun `method rule blocks only the matching frame method`() {
        val checker = DynamicExtentChecker(
            BlocklistEngine(registryOf(BlocklistRule.Method("com.corp.Auth", "login")))
        )
        assertNotNull(checker.firstBlockedFrame(listOf(frame("com.corp.Auth", "login"))))
        assertNull(checker.firstBlockedFrame(listOf(frame("com.corp.Auth", "logout"))))
    }

    @Test
    fun `verdicts are memoized until invalidate`() {
        val registry = SensitiveAreaBlocklistRegistry()
        val checker = DynamicExtentChecker(BlocklistEngine(registry))
        // Non-empty policy so isEmpty() doesn't short-circuit; `blocked` classifies CLEAR and is memoized.
        registry.add(listOf(SensitiveAreaBlocklist.of("t1", listOf(BlocklistRule.Class("com.other.X")))))
        assertNull(checker.firstBlockedFrame(listOf(frame(blocked))))

        // Policy tightens and now blocks `blocked` — the stale memo still answers CLEAR until invalidated.
        registry.add(listOf(SensitiveAreaBlocklist.of("t2", listOf(BlocklistRule.Class(blocked)))))
        assertNull(checker.firstBlockedFrame(listOf(frame(blocked))))

        checker.invalidate()
        assertNotNull(checker.firstBlockedFrame(listOf(frame(blocked))))
    }
}
