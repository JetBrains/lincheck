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
 * Checks that the agent keeps working when the traced application ships its own, older Kotlin runtime.
 *
 * A `-javaagent` jar is *appended* to the system class path, so without an isolated classloader the
 * application's `kotlin-stdlib` shadows the one bundled in the agent, and the agent links against it.
 * The Kotlin version that makes that linkage fail is pinned in
 * `integration-test/test-projects/kotlin-classpath-clash/build.gradle`.
 */
class AgentClasspathIsolationIntegrationTest : AbstractGradleTraceIntegrationTest() {
    override val projectPath: String =
        Paths.get("build", "integrationTestProjects", "kotlin-classpath-clash").toString()

    @Test
    fun conflictingKotlinRuntimeDoesNotBreakTracing() {
        runTest(
            testClassName = "ClasspathClashTest",
            testMethodName = "capturesKotlinValues",
            commands = listOf(":test"),
            checkRepresentation = false,
        )
    }
}
