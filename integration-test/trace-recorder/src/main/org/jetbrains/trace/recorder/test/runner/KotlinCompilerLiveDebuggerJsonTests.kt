/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test.runner

import AbstractGradleLiveDebuggerIntegrationTest
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.createTempFile

abstract class KotlinCompilerLiveDebuggerJsonTests : AbstractGradleLiveDebuggerIntegrationTest() {
    override val projectPath = Paths.get("build", "integrationTestProjects", "kotlin").toString()

    override fun runTestImpl(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
        commands: List<String>,
        outputFile: File
    ) {
        val permissions = createTempFile("permissions", "txt").toFile()
        try {
            permissions.writeText(
                """
                    grant codeBase "file:/-" {
                        permission java.security.AllPermission;
                    };
                """.trimIndent()
            )
            val allJvmArgs = listOf("-Djava.security.policy==${permissions.absolutePath}") + extraJvmArgs
            super.runTestImpl(testClassName, testMethodName, extraJvmArgs = allJvmArgs, extraAgentArgs, commands, outputFile)
        } finally {
            permissions.delete()
        }
    }

    companion object Companion : TestGenerator(
        groupName = "KotlinCompilerLiveDebugger",
        resourcePath = "/integrationTestData/kotlinCompilerLiveDebuggerTests.json",
        abstractTestClass = "KotlinCompilerLiveDebuggerJsonTests",
        packageName = "org.jetbrains.trace.recorder.test.impl.generated",
    )
}
