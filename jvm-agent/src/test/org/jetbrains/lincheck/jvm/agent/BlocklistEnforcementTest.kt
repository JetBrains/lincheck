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

import org.jetbrains.lincheck.jvm.agent.InstrumentationMode.LIVE_DEBUGGING
import org.jetbrains.lincheck.jvm.agent.blocklist.BlocklistEngine
import org.jetbrains.lincheck.jvm.agent.fixtures.JavaIfElseMultiLineFixture
import org.jetbrains.lincheck.settings.BlocklistRule
import org.jetbrains.lincheck.settings.LiveDebuggerSettings
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.trace.TraceContext
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.nio.ch.lincheck.BreakpointStorage
import java.util.UUID

/** End-to-end enforcement tests for the three blocklist stages (registration, instrumentation, capture). */
class BlocklistEnforcementTest {

    private val fixtureClass = JavaIfElseMultiLineFixture::class.java
    private val fixtureName = fixtureClass.name
    private val blockedLine = 19 // `s = 1;` inside ifElseMultiLine

    private fun breakpoint(className: String = fixtureName, line: Int = blockedLine) = SnapshotBreakpoint(
        uuid = UUID.randomUUID(),
        className = className,
        fileName = "JavaIfElseMultiLineFixture.java",
        lineNumber = line,
    )

    private fun blocklist(vararg rules: BlocklistRule) =
        SensitiveAreaBlocklist.of("test", rules.toList())

    // ---- Stage 1: registration-time rejection ----

    @Test
    fun `stage 1 rejects a breakpoint whose class is statically blocked`() {
        val settings = LiveDebuggerSettings()
        settings.blocklistRegistry.add(listOf(blocklist(BlocklistRule.Class(fixtureName))))

        val result = settings.addBreakpoints(listOf(breakpoint()))

        assertTrue(result.added.isEmpty())
        assertEquals(1, result.rejected.size)
        assertEquals("test", result.rejected.single().match.blocklistName)
        assertTrue(settings.lineBreakpoints.isEmpty())
    }

    @Test
    fun `stage 1 registers a breakpoint outside any blocked area`() {
        val settings = LiveDebuggerSettings()
        settings.blocklistRegistry.add(listOf(blocklist(BlocklistRule.Package("com.corp.crypto"))))

        val result = settings.addBreakpoints(listOf(breakpoint()))

        assertEquals(1, result.added.size)
        assertTrue(result.rejected.isEmpty())
    }

    // ---- Stage 2: instrumentation-time suppression ----

    @Test
    fun `stage 2 injects a hook when nothing is blocked`() {
        val count = transformHookCount(breakpoints = listOf(breakpoint()))
        assertTrue("expected at least one snapshot hook, got $count", count >= 1)
    }

    @Test
    fun `stage 2 suppresses the hook when the whole class is blocked`() {
        val count = transformHookCount(
            breakpoints = listOf(breakpoint()),
            blocklists = listOf(blocklist(BlocklistRule.Class(fixtureName))),
        )
        assertEquals(0, count)
    }

    @Test
    fun `stage 2 suppresses the hook when the package is blocked`() {
        val count = transformHookCount(
            breakpoints = listOf(breakpoint()),
            blocklists = listOf(blocklist(BlocklistRule.Package("org.jetbrains.lincheck.jvm.agent.fixtures"))),
        )
        assertEquals(0, count)
    }

    @Test
    fun `stage 2 suppresses the hook when the enclosing method is blocked`() {
        val count = transformHookCount(
            breakpoints = listOf(breakpoint()),
            blocklists = listOf(blocklist(BlocklistRule.Method(fixtureName, "ifElseMultiLine"))),
        )
        assertEquals(0, count)
    }

    @Test
    fun `stage 2 keeps the hook when a different method is blocked`() {
        val count = transformHookCount(
            breakpoints = listOf(breakpoint()),
            blocklists = listOf(blocklist(BlocklistRule.Method(fixtureName, "someOtherMethod"))),
        )
        assertTrue(count >= 1)
    }

    // ---- Stage 3: dynamic-extent capture guard plumbing ----

    @Test
    fun `stage 3 hit-limit peek is non-mutating`() {
        val id = 987_655
        try {
            BreakpointStorage.clear()
            BreakpointStorage.registerBreakpoint(id, 2, "payload")

            // Peeking any number of times consumes no budget…
            repeat(10) { assertFalse(BreakpointStorage.isHitLimitReached(id)) }
            // …so both budgeted hits are still available.
            assertTrue(BreakpointStorage.incrementAndCheckHitLimit(id))
            assertTrue(BreakpointStorage.incrementAndCheckHitLimit(id))
            assertTrue(BreakpointStorage.isHitLimitReached(id))
            assertFalse(BreakpointStorage.incrementAndCheckHitLimit(id))
        } finally {
            BreakpointStorage.clear()
        }
    }

    @Test
    fun `stage 3 hit-limit peek reports an unregistered breakpoint as exhausted`() {
        BreakpointStorage.clear()
        assertTrue(BreakpointStorage.isHitLimitReached(123_456_789))
    }

    /**
     * Runs the live-debugger transformation over the fixture with the given breakpoints and active
     * [blocklists], and returns the number of injected `onSnapshotLineBreakpoint` hooks.
     */
    private fun transformHookCount(
        breakpoints: List<SnapshotBreakpoint>,
        blocklists: List<SensitiveAreaBlocklist> = emptyList(),
    ): Int {
        val internalName = fixtureName.replace('.', '/')
        val classBytes = requireNotNull(
            javaClass.classLoader.getResourceAsStream("$internalName.class")
        ) { "fixture not on classpath: $internalName" }.use { it.readBytes() }

        val reader = ClassReader(classBytes)
        val writer = SafeClassWriter(reader, javaClass.classLoader, ClassWriter.COMPUTE_FRAMES)
        val classNode = ClassNode()
        reader.accept(classNode, ClassReader.EXPAND_FRAMES)

        val settings = LiveDebuggerSettings(breakpoints)
        settings.blocklistRegistry.add(blocklists)
        val profile = LiveDebuggerTransformationProfile(settings)
        val blocklistEngine = BlocklistEngine(settings.blocklistRegistry)
        val classInformation = buildClassInformation(classNode, reader, profile, blocklistEngine, settings)

        classNode.accept(
            LincheckClassVisitor(
                classVisitor = writer,
                classInformation = classInformation,
                instrumentationMode = LIVE_DEBUGGING,
                profile = profile,
                statsTracker = null,
                context = TraceContext(),
            ),
        )
        return writer.toByteArray().snapshotHookInvocationCount()
    }
}
