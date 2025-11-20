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

import AbstractGradleTraceIntegrationTest
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class KotlinDatetimeTraceRecorderIntegrationTest : AbstractGradleTraceIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "kotlinx-datetime").toString()

    // This test checks that the trace-recorder agent does not fail because of
    // the turned-on kover agent and that all coverage instructions are filtered out.
    // See JBRes-6555 for details.
    @Test
    fun `kotlinx_datetime_test_ConvertersTest instant`() {
        runTest(
            testClassName = "kotlinx.datetime.test.ConvertersTest",
            testMethodName = "instant",
            commands = listOf(":kotlinx-datetime:jvmTest")
        )
    }
}