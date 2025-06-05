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

import org.jetbrains.kotlinx.lincheck_test.util.OVERWRITE_REPRESENTATION_TESTS_OUTPUT
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.gradle.tooling.GradleConnector
import org.junit.Assert

abstract class AbstractTraceDebuggerIntegrationTest {
    abstract val projectPath: String

    private fun buildGradleInitScriptToDumpTrace(
        testClassName: String,
        testMethodName: String,
        fileToDump: File
    ): String {
        val pathToFatJar = File("build/libs/lincheck-fat.jar")
        return """
            gradle.taskGraph.whenReady {
                val jvmTasks = allTasks.filter { task -> task is JavaForkOptions }
                jvmTasks.forEach { task ->
                    task.doFirst {
                        val options = task as JavaForkOptions
                        val jvmArgs = options.jvmArgs?.toMutableList() ?: mutableListOf()
                        jvmArgs.add("-Dlincheck.traceDebuggerMode=true")
                        jvmArgs.add("-javaagent:${pathToFatJar.absolutePath}=$testClassName,$testMethodName,${fileToDump.absolutePath}")
                        
                        jvmArgs.add("-XX:+UnlockExperimentalVMOptions") // Enables -XX:hashCode
                        jvmArgs.add("-XX:hashCode=2") // Use constant 1 for hash code
                        options.jvmArgs = jvmArgs
                    }
                }
            }
        """.trimIndent()
    }

    private fun getGolderDataFileFor(testClassName: String, testMethodName: String): File {
        val projectName = File(projectPath).name
        return File("src/jvm/test-trace-debugger-integration/resources/integrationTestData/$projectName/${testClassName}_$testMethodName.txt")
    }

    private fun createInitScriptAsTempFile(content: String): File {
        val tempFile = File.createTempFile("initScript", ".gradle.kts")
        tempFile.writeText(content)
        return tempFile
    }

    protected fun runGradleTest(
        testClassName: String,
        testMethodName: String,
        gradleCommands: List<String>,
    ) {
        val tmpFile = File.createTempFile(testClassName + "_" + testMethodName, "")

        GradleConnector.newConnector().forProjectDirectory(File(projectPath)).connect().use { connection ->
            connection
                .newBuild()
                .addArguments(
                    "--init-script",
                    createInitScriptAsTempFile(buildGradleInitScriptToDumpTrace(testClassName, testMethodName, tmpFile)).absolutePath,
                ).forTasks(
                    *gradleCommands.toTypedArray(),
                    "--tests",
                    "$testClassName.$testMethodName",
                ).run()
        }

        // TODO decide how to test: with gold data or run twice?
        val expectedOutput = getGolderDataFileFor(testClassName, testMethodName)
        if (expectedOutput.exists()) {
            Assert.assertEquals(expectedOutput.readText(), tmpFile.readText())
        } else {
            if (OVERWRITE_REPRESENTATION_TESTS_OUTPUT) {
                expectedOutput.parentFile.mkdirs()
                copy(tmpFile, expectedOutput)
                Assert.fail("The gold data file was created. Please rerun the test.")
            } else {
                Assert.fail("The gold data file was not found. " +
                        "Please rerun the test with \"overwriteRepresentationTestsOutput\" option enabled.")
            }
        }
    }

    private fun copy(srcFile: File, destFile: File) {
        val src = FileInputStream(srcFile).getChannel()
        val dest = FileOutputStream(destFile).getChannel()
        dest.transferFrom(src, 0, src.size())
    }
}
