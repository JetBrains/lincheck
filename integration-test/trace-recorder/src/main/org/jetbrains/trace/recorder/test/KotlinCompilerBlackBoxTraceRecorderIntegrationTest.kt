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
import kotlin.io.path.createTempFile

class KotlinCompilerBlackBoxTraceRecorderIntegrationTest : AbstractTraceRecorderIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "kotlin").toString()

    @Test
    fun `org_jetbrains_kotlin_test_runners_codegen_FirLightTreeBlackBoxCodegenTestGenerated testAllFilesPresentInBox`() {
        val permissions = createTempFile("permissions", "txt").toFile()
        // grant all permissions to all code bases located (recursively as well) in directory '/'
        permissions.writeText("""
            grant codeBase "file:/-" {
                permission java.security.AllPermission;
            };
        """.trimIndent())
        runGradleTest(
            testClassName = "org.jetbrains.kotlin.test.runners.codegen.FirLightTreeBlackBoxCodegenTestGenerated",
            testMethodName = "testAllFilesPresentInBox",
            gradleCommands = listOf(":compiler:fir:fir2ir:test"),
            // kotlin compiler complains about permissions of the attached agent,
            // so we specify our own permissions file
            extraJvmArgs = listOf("-Djava.security.policy==${permissions.absolutePath}")
        )
        permissions.delete()
    }
}