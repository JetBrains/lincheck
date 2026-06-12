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

import AbstractWholeApplicationTraceIntegrationTest
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Integration tests for whole-application tracing mode: the agent is launched with
 * `class=""` / `method=""` arguments set to empty strings, so it traces
 * the full JVM lifetime and stops on JVM exit via the shutdown hook.
 */
class TraceDebuggerExamplesWholeApplicationTraceRecorderIntegrationTest : AbstractWholeApplicationTraceIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "TraceDebuggerExamples").toString()

    override val goldenDataFolderName: String = "TraceDebuggerExamples.wholeAppTracing"

    @Test
    fun specificTestIsTracedInWholeAppMode() {
        runTest(
            testClassName = "org.examples.hackathon.SimpleProgramNonFailingTest",
            testMethodName = "test",
            commands = listOf(":test"),
            checkRepresentation = true,
            extraJvmArgs = listOf("-Xshare:off") // disable Class Data Sharing so that System.identityHashCode for Integer becomes deterministic
        )
    }

    @Test
    fun `org_examples_integration_bugs_ThreadsTest daemonThreadTest`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ThreadsTest",
            testMethodName = "daemonThreadTest",
            commands = listOf(":test"),
            checkRepresentation = true,
        )
    }
}
