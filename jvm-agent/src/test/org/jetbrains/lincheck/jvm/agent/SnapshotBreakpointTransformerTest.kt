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
import org.jetbrains.lincheck.jvm.agent.transformers.SnapshotBreakpointTransformer
import org.jetbrains.lincheck.settings.LiveDebuggerSettings
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.trace.TraceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.UUID

/**
 * Tests for [SnapshotBreakpointTransformer]. 
 *
 * Tests here drive the transformer through the minimized transformation pipeline.
 * Adding a new test case is a matter of adding a new fixture class under `src/test/.../fixtures/`
 * and a new `@Test` method that calls [transformWithSnapshotBreakpoints].
 *
 * Current test cases:
 *     - JBRes-9243: a breakpoint set on a Kotlin lambda source line must reach
 *                   the generated JVM class that contains the lambda body.
 */
class SnapshotBreakpointTransformerTest {

    @Test
    fun liveDebuggerProfileAcceptsOwnerAndGeneratedDescendants() {
        val fixture = LambdaFixtureClass
        val profile = LiveDebuggerTransformationProfile(
            LiveDebuggerSettings(
                listOf(snapshotBreakpoint(fixture.outerCanonicalName, fixture.fileName, line = 1))
            )
        )

        assertTrue(profile.shouldTransform(fixture.outerCanonicalName))
        assertTrue(profile.shouldTransform(fixture.lambdaCanonicalName))
        assertFalse(profile.shouldTransform("java.util.ArrayList"))
    }

    @Test
    fun snapshotBreakpointsAreInjectedIntoOwnerAndGeneratedClasses() {
        val fixture = LambdaFixtureClass
        // Two breakpoints, both owned by the outer class: one on a line that lives
        // in the outer class body and one on a line that lives only in the lambda body.
        val breakpoints = listOf(
            snapshotBreakpoint(fixture.outerCanonicalName, fixture.fileName, line = 27),
            snapshotBreakpoint(fixture.outerCanonicalName, fixture.fileName, line = 29),
        )
        assertSnapshotHookInjected(fixture.outerInternalName, breakpoints)
        assertSnapshotHookInjected(fixture.lambdaInternalName, breakpoints)
    }

    private fun assertSnapshotHookInjected(
        internalClassName: String,
        breakpoints: List<SnapshotBreakpoint>,
    ) {
        val transformed = transformWithSnapshotBreakpoints(internalClassName, breakpoints)
        val hookCount = transformed.snapshotHookInvocationCount()
        assertTrue(
            "$internalClassName must contain Injections.onSnapshotLineBreakpoint, got=$hookCount",
            hookCount >= 1,
        )
    }

    @Suppress("unused") // kept for future negative-case tests
    private fun assertNoSnapshotHookInjected(
        internalClassName: String,
        breakpoints: List<SnapshotBreakpoint>,
    ) {
        val transformed = transformWithSnapshotBreakpoints(internalClassName, breakpoints)
        assertEquals(
            "$internalClassName must not contain any Injections.onSnapshotLineBreakpoint call",
            0, transformed.snapshotHookInvocationCount(),
        )
    }

    private object LambdaFixtureClass {
        const val fileName = "LambdaFixture.kt"
        const val outerCanonicalName = "org.jetbrains.kotlinx.lincheck_test.fixtures.LambdaFixture"
        const val outerInternalName = "org/jetbrains/kotlinx/lincheck_test/fixtures/LambdaFixture"
        const val lambdaCanonicalName = "$outerCanonicalName\$run\$1"
        const val lambdaInternalName = "$outerInternalName\$run\$1"
    }
}

/**
 * Creates a [SnapshotBreakpoint] with no condition attached. Tests that need
 * a conditional breakpoint should construct [SnapshotBreakpoint] directly.
 */
internal fun snapshotBreakpoint(
    className: String,
    fileName: String,
    line: Int,
): SnapshotBreakpoint = SnapshotBreakpoint(
    uuid = UUID.randomUUID(),
    className = className,
    fileName = fileName,
    lineNumber = line,
    conditionClassName = null,
    conditionFactoryMethodName = null,
    conditionCapturedVars = null,
    conditionCodeFragment = null,
)

/**
 * Reads `$internalClassName.class` from the test classpath, runs the
 * production [LincheckClassVisitor] with [LiveDebuggerTransformationProfile]
 * (which gates everything except [SnapshotBreakpointTransformer] off), and
 * returns the resulting bytes.
 *
 * Driving the production visitor — instead of a custom test-only `ClassVisitor` —
 * keeps the test pipeline aligned with what the agent actually applies at
 * runtime, so changes to the live-debugger transformer chain stay covered here
 * automatically.
 */
internal fun transformWithSnapshotBreakpoints(
    internalClassName: String,
    breakpoints: List<SnapshotBreakpoint>,
): ByteArray {
    val classBytes = readClassBytes(internalClassName)
    val reader = ClassReader(classBytes)
    val classLoader = Thread.currentThread().contextClassLoader
    val writer = SafeClassWriter(reader, classLoader, ClassWriter.COMPUTE_FRAMES)

    val liveDebuggerSettings = LiveDebuggerSettings(breakpoints)
    val profile = LiveDebuggerTransformationProfile(liveDebuggerSettings)

    // TODO: extract the per-class preprocessing currently done in
    //   `LincheckClassFileTransformer` (SMAP / locals / labels / line-ranges / basic-block CFG)
    //   into a reusable helper so tests can pass a fully populated `ClassInformation` here.
    //   For the snapshot-breakpoint transformer specifically, the unconditional path doesn't read any of these,
    //   so empty data is sufficient today;
    //   conditional-breakpoint coverage will need the real preprocessing.
    val classInformation = ClassInformation(
        smap = SMAPInfo(""),
        locals = emptyMap(),
        labels = emptyMap(),
        methodsToLineRanges = emptyMap(),
        linesToMethodNames = emptyList(),
        nonSyntheticMethodLines = emptySet(),
        basicCfgs = emptyMap(),
    )

    reader.accept(
        LincheckClassVisitor(
            classVisitor = writer,
            classInformation = classInformation,
            instrumentationMode = LIVE_DEBUGGING,
            profile = profile,
            statsTracker = null,
            liveDebuggerSettings = liveDebuggerSettings,
            context = TraceContext(),
        ),
        ClassReader.EXPAND_FRAMES,
    )
    return writer.toByteArray()
}

/**
 * Counts INVOKESTATIC calls to `Injections.onSnapshotLineBreakpoint` in the
 * given class bytes. The transformer emits one such call per instrumented
 * source line, so this is the simplest indicator that a breakpoint was wired
 * into the bytecode.
 */
internal fun ByteArray.snapshotHookInvocationCount(): Int {
    var count = 0
    ClassReader(this).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int, name: String?, desc: String?, sig: String?, ex: Array<out String>?,
        ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
            override fun visitMethodInsn(
                opcode: Int,
                owner: String?,
                name: String?,
                descriptor: String?,
                isInterface: Boolean,
            ) {
                if (opcode == Opcodes.INVOKESTATIC &&
                    owner == "sun/nio/ch/lincheck/Injections" &&
                    name == "onSnapshotLineBreakpoint"
                ) count++
            }
        }
    }, 0)
    return count
}

private fun readClassBytes(internalClassName: String): ByteArray =
    requireNotNull(
        Thread.currentThread().contextClassLoader.getResourceAsStream("$internalClassName.class")
    ) { "Could not find $internalClassName.class on the test classpath" }
        .use { it.readBytes() }
