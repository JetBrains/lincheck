/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.trace_debugger.integration

import org.junit.Ignore
import org.junit.Test

class KtorTraceDebuggerIntegrationTest: AbstractTraceDebuggerIntegrationTest() {
    override val projectPath: String = "build/integrationTestProjects/ktor"

    @Ignore("https://github.com/JetBrains/lincheck/issues/685")
    @Test
    fun testReadAvailableBlockFromClosed() {
        runGradleTest(
            testClassName ="ByteReadChannelOperationsJvmTest",
            testMethodName = "testReadAvailableBlockFromClosed",
            gradleCommands = listOf(
                ":ktor-io:cleanJvmTest",
                ":ktor-io:jvmTest",
            )
        )
    }
}
