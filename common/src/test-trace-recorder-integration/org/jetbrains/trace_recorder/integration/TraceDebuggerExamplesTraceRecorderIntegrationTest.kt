/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace_recorder.integration

import org.junit.Test
import java.nio.file.Paths

class TraceDebuggerExamplesTraceRecorderIntegrationTest : AbstractTraceRecorderIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "TraceDebuggerExamples").toString()

    @Test
    fun `org_examples_integration_bugs_LinkedHashSetTest putAnObjectWithoutDefinedHashCode`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.LinkedHashSetTest",
            testMethodName = "putAnObjectWithoutDefinedHashCode",
            gradleCommands = listOf(":test"),
        )
    }

    @Test // actually runs the same test as above, but via the `runGradleTests` call
    fun `org_examples_integration_bugs_LinkedHashSetTest`() {
        runGradleTests(
            testClassNamePrefix = "org.examples.integration.bugs.LinkedHashSetTest",
            gradleBuildCommands = listOf("compileTestKotlin"),
            gradleTestCommands = listOf(":test"),
            checkRepresentation = false,
        )
    }
}
