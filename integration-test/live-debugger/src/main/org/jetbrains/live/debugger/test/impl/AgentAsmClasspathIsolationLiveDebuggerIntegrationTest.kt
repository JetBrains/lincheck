/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.live.debugger.test.impl

import AbstractGradleLiveDebuggerIntegrationTest
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Checks that the agent keeps working when the debugged application ships its own, older ASM.
 *
 * A `-javaagent` jar is *appended* to the system class path, so without an isolated classloader the
 * application's ASM shadows the one bundled in the agent, and the agent's instrumentation links against it.
 * The ASM version that makes that linkage fail is pinned in
 * `integration-test/test-projects/asm-classpath-clash/build.gradle`.
 *
 * The live debugger swallows a broken agent startup instead of failing the debugged JVM,
 * so the regression shows up as an empty trace, which is what this test asserts against.
 */
class AgentAsmClasspathIsolationLiveDebuggerIntegrationTest : AbstractGradleLiveDebuggerIntegrationTest() {
    override val projectPath: String =
        Paths.get("build", "integrationTestProjects", "asm-classpath-clash").toString()

    @Test
    fun conflictingAsmDoesNotBreakDebugging() {
        runTest(
            testClassName = "AsmClasspathClashTest",
            testMethodName = "generatesBytecodeWithOwnAsm",
            commands = listOf(":test"),
            checkRepresentation = false,
            breakpointsIni = """
                [Breakpoint 1]
                uuid = 00000000-0000-0000-0002-000000000001
                className = AsmClasspathClashTest
                fileName = AsmClasspathClashTest.java
                lineNumber = 13
            """.trimIndent(),
        )
    }
}
