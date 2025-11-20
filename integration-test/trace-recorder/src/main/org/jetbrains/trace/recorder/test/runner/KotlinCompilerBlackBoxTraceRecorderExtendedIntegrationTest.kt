/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test.runner

import java.io.File
import java.nio.file.Paths
import kotlin.io.path.createTempFile

abstract class KotlinCompilerTraceRecorderJsonTests : AbstractJsonTraceRecorderIntegrationTest(
    projectPath = Paths.get("build", "integrationTestProjects", "kotlin").toString(),
    checkRepresentationByDefault = false,
) {
    override fun runTestImpl(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
        commands: List<String>,
        outputFile: File
    ) {
        withPermissions { permissions ->
            val allJvmArgs = listOf("-Djava.security.policy==${permissions.absolutePath}") + extraJvmArgs
            super.runTestImpl(testClassName, testMethodName, extraJvmArgs = allJvmArgs, extraAgentArgs, commands, outputFile)
        }
    }

    companion object Companion : TestGenerator(
        groupName = "KotlinCompiler",
        resourcePath = "/integrationTestData/kotlinCompilerTests.json",
        abstractTestClass = "KotlinCompilerTraceRecorderJsonTests",
        packageName = "org.jetbrains.trace.recorder.test.impl.generated",
    )
}

private fun <T> withPermissions(block: (File) -> T): T {
    val permissions = createTempFile("permissions", "txt").toFile()
    return try {
        permissions.writeText(
            """
                    grant codeBase "file:/-" {
                        permission java.security.AllPermission;
                    };
                """.trimIndent()
        )
        block(permissions)
    } finally {
        permissions.delete()
    }
}
