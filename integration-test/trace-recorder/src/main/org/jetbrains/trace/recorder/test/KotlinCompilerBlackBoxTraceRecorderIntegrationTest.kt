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
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.createTempFile

class KotlinCompilerBlackBoxTraceRecorderIntegrationTest : AbstractTraceRecorderIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "kotlin").toString()
    override val formatArgs: Map<String, String> = mapOf("format" to "binary", "formatOption" to "stream")
    
    private fun <T> withPermissions(block: (File) -> T) : T {
        val permissions = createTempFile("permissions", "txt").toFile()
        return try {
            // grant all permissions to all code bases located (recursively as well) in directory '/'
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
    
    private fun runKotlinCompilerTestSuite(task: String, className: String, methodName: String, checkRepresentation: Boolean = false) =
        withPermissions { permissions ->
            runGradleTest(
                testClassName = className,
                testMethodName = methodName,
                extraJvmArgs = listOf("-Djava.security.policy==${permissions.absolutePath}"),
                // kotlin compiler complains about permissions of the attached agent,
                // so we specify our own permissions file
                gradleCommands = listOf(task),
                checkRepresentation = checkRepresentation,
            )
        }

    private val jvmBoxSuite = "org.jetbrains.kotlin.test.runners.codegen.FirLightTreeBlackBoxCodegenTestGenerated"
    private val jsBoxSuite = "org.jetbrains.kotlin.js.test.fir.FirJsCodegenBoxTestGenerated"
    private val frontendSuite = "org.jetbrains.kotlin.test.runners.PhasedJvmDiagnosticLightTreeTestGenerated"
    private val jvmBoxTask = ":compiler:fir:fir2ir:test"
    private val jsBoxTask = ":js:js.tests:test"
    private val frontendTask = ":compiler:fir:analysis-tests:test"

    @Test
    fun `jvmBoxSuite testAllFilesPresentInBox`() = runKotlinCompilerTestSuite(
        task = jvmBoxTask, className = jvmBoxSuite, methodName = "testAllFilesPresentInBox",
    )

    @Test
    fun `jvmBoxSuite-Annotations testAllowedTargets`() = runKotlinCompilerTestSuite(
        task = jvmBoxTask, className = $$"$$jvmBoxSuite$Annotations", methodName = "testAllowedTargets",
    )

    @Test
    fun `jsBoxSuite testAllFilesPresentInBox`() = runKotlinCompilerTestSuite(
        task = jsBoxTask, className = jsBoxSuite, methodName = "testAllFilesPresentInBox",
    )

    @Test
    @Ignore("Takes too long")
    fun `jsBoxSuite-Annotations testAnnotations0`() = runKotlinCompilerTestSuite(
        task = jsBoxTask, className = $$"$$jsBoxSuite$Annotations", methodName = "testAnnotations0",
    )
    
    @Test
    fun `frontendSuite testAnnotationConstructorDefaultParameter`() = runKotlinCompilerTestSuite(
        task = frontendTask,
        className = $$"$$frontendSuite$Tests$Annotations$AnnotationParameterMustBeConstant",
        methodName = "testAnnotationConstructorDefaultParameter",
    )
}
