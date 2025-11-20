/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test.impl

import org.jetbrains.trace.recorder.test.runner.AbstractTraceRecorderIntegrationTest
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class KotlinCoroutinesTraceRecorderIntegrationTest : AbstractTraceRecorderIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "kotlinx.coroutines").toString()

    @Test
    @Disabled("Flaky test")
    fun `kotlinx_coroutines_ExecutorsTest testDefaultDispatcherToExecutor`() {
        runTest(
            testClassName = "kotlinx.coroutines.ExecutorsTest",
            testMethodName = "testDefaultDispatcherToExecutor",
            commands = listOf(
                ":kotlinx-coroutines-core:jvmTest",
            )
        )
    }
}