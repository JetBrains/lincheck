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
 * Checks that the agent keeps working when the debugged application ships its own, older Kotlin runtime.
 *
 * A `-javaagent` jar is *appended* to the system class path, so without an isolated classloader the
 * application's `kotlin-stdlib` shadows the one bundled in the agent, and the agent links against it.
 * The Kotlin version that makes that linkage fail is pinned in
 * `integration-test/test-projects/kotlin-classpath-clash/build.gradle`.
 *
 * The live debugger swallows a broken agent startup instead of failing the debugged JVM,
 * so the regression shows up as an empty trace, which is what this test asserts against.
 */
class AgentKotlinStdlibClasspathIsolationLiveDebuggerIntegrationTest : AbstractGradleLiveDebuggerIntegrationTest() {
    override val projectPath: String =
        Paths.get("build", "integrationTestProjects", "kotlin-classpath-clash").toString()

    @Test
    fun conflictingKotlinRuntimeDoesNotBreakDebugging() {
        runTest(
            testClassName = "ClasspathClashTest",
            testMethodName = "capturesKotlinValues",
            commands = listOf(":test"),
            checkRepresentation = false,
            breakpointsIni = """
                [Breakpoint 1]
                uuid = 00000000-0000-0000-0001-000000000001
                className = ClasspathClashTest
                fileName = ClasspathClashTest.java
                lineNumber = 11
            """.trimIndent(),
        )
    }
}
