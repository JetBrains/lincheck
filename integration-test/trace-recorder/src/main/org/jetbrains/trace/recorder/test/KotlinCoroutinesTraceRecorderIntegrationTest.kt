/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test

import org.junit.Ignore
import org.junit.Test
import java.nio.file.Paths

class KotlinCoroutinesTraceRecorderIntegrationTest : AbstractTraceRecorderIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "kotlinx.coroutines").toString()

    @Test
    fun `kotlinx_coroutines_ExecutorsTest testDefaultDispatcherToExecutor`() {
        runGradleTest(
            testClassName = "kotlinx.coroutines.ExecutorsTest",
            testMethodName = "testDefaultDispatcherToExecutor",
            gradleCommands = listOf(
                ":kotlinx-coroutines-core:jvmTest",
            )
        )
    }
}