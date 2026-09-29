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
import java.util.Base64

class AgentJavaExpressionsLiveDebuggerIntegrationTest : AbstractGradleLiveDebuggerIntegrationTest() {
    override val projectPath: String =
        Paths.get("build", "integrationTestProjects", "agent-expressions").toString()

    @Test
    fun inheritedPrivateFieldWatchIsCaptured() {
        val watches = listOf("owner.lastName", "owner").joinToString(";") {
            Base64.getEncoder().encodeToString(it.toByteArray())
        }
        runTest(
            testClassName = "SourceWatchesTest",
            testMethodName = "capturesOwner",
            commands = listOf(":test"),
            breakpointsIni = """
                [Breakpoint 1]
                uuid = 00000000-0000-0000-0003-000000000001
                className = SourceWatchesTest
                fileName = SourceWatchesTest.java
                lineNumber = 12
                expressionLanguage = JAVA
                watchLabels = $watches
                watchSources = $watches
            """.trimIndent(),
        )
    }
}
