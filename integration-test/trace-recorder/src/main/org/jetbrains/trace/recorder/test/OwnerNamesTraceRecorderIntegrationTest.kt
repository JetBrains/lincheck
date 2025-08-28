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

import org.junit.Test
import java.nio.file.Paths
import kotlin.String

class OwnerNamesTraceRecorderIntegrationTest : AbstractTraceRecorderIntegrationTest() {
    override val projectPath: String = Paths.get("./").toString()

    private val gradleCommands = listOf(
        ":integration-test:trace-recorder:clean",
        ":integration-test:trace-recorder:traceRecorderIntegrationTest",
    )

    @Test
    fun `test_custom_OwnerNamesTest testArrays`() {
        runGradleTest(
            testClassName = "org.jetbrains.trace.recorder.test.custom.OwnerNamesTest",
            testMethodName = "testArrays",
            gradleCommands = gradleCommands
        )
    }

    @Test
    fun `test_custom_OwnerNamesTest testLocalVariables`() {
        runGradleTest(
            testClassName = "org.jetbrains.trace.recorder.test.custom.OwnerNamesTest",
            testMethodName = "testLocalVariables",
            gradleCommands = gradleCommands
        )
    }

    @Test
    fun `test_custom_OwnerNamesTest testFields`() {
        runGradleTest(
            testClassName = "org.jetbrains.trace.recorder.test.custom.OwnerNamesTest",
            testMethodName = "testFields",
            gradleCommands = gradleCommands
        )
    }
}