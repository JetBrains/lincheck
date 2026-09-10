/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test.impl

import AbstractGradleTraceIntegrationTest
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Checks that the agent keeps working when the traced application ships its own, older ASM.
 *
 * A `-javaagent` jar is *appended* to the system class path, so without an isolated classloader the
 * application's ASM shadows the one bundled in the agent, and the agent's instrumentation links against it.
 * The ASM version that makes that linkage fail is pinned in
 * `integration-test/test-projects/asm-classpath-clash/build.gradle`.
 */
class AgentAsmClasspathIsolationIntegrationTest : AbstractGradleTraceIntegrationTest() {
    override val projectPath: String =
        Paths.get("build", "integrationTestProjects", "asm-classpath-clash").toString()

    @Test
    fun conflictingAsmDoesNotBreakTracing() {
        runTest(
            testClassName = "AsmClasspathClashTest",
            testMethodName = "generatesBytecodeWithOwnAsm",
            commands = listOf(":test"),
            checkRepresentation = false,
        )
    }
}
