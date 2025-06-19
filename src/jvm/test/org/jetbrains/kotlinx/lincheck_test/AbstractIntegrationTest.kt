/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import org.gradle.tooling.GradleConnector
import org.jetbrains.kotlinx.lincheck_test.util.OVERWRITE_REPRESENTATION_TESTS_OUTPUT
import org.junit.Assert
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Paths

abstract class AbstractIntegrationTest {
    abstract val projectPath: String
    protected abstract val testSourcesPath: String

    private fun buildGradleInitScriptToDumpTrace(
        testClassName: String,
        testMethodName: String,
        fileToDump: File,
        extraJvmArgs: List<String>,
        extraAgentArgs: List<String>,
    ): String {
        val pathToFatJar = File(Paths.get("build", "libs", "lincheck-fat.jar").toString())
        return """
            gradle.taskGraph.whenReady {
                val jvmTasks = allTasks.filter { task -> task is JavaForkOptions }
                jvmTasks.forEach { task ->
                    task.doFirst {
                        val options = task as JavaForkOptions
                        val jvmArgs = options.jvmArgs?.toMutableList() ?: mutableListOf()
                        jvmArgs.addAll(listOf(${extraJvmArgs.joinToString(", ") { "\"$it\"" }}))
                        jvmArgs.add("-javaagent:${pathToFatJar.absolutePath}=$testClassName,$testMethodName,${fileToDump.absolutePath}${if (extraAgentArgs.isNotEmpty()) ",${extraAgentArgs.joinToString(",")}" else ""}")
                        options.jvmArgs = jvmArgs
                    }
                }
            }
        """.trimIndent()
    }

    private fun getGolderDataFileFor(testClassName: String, testMethodName: String): File {
        val projectName = File(projectPath).name
        return File(Paths.get(testSourcesPath, "resources", "integrationTestData", projectName, "${testClassName}_$testMethodName.txt").toString())
    }

    private fun createInitScriptAsTempFile(content: String): File {
        val tempFile = File.createTempFile("initScript", ".gradle.kts")
        tempFile.writeText(content)
        return tempFile
    }

    abstract fun runGradleTest(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String> = emptyList(),
        extraAgentArgs: List<String> = emptyList(),
        gradleCommands: List<String>,
    )

    protected fun runGradleTestImpl(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String> = emptyList(),
        extraAgentArgs: List<String> = emptyList(),
        gradleCommands: List<String>,
    ) {
        val tmpFile = File.createTempFile(testClassName + "_" + testMethodName, "")

        GradleConnector.newConnector().forProjectDirectory(File(projectPath)).connect().use { connection ->
            connection
                .newBuild()
                .addArguments(
                    "--init-script",
                    createInitScriptAsTempFile(buildGradleInitScriptToDumpTrace(testClassName, testMethodName, tmpFile, extraJvmArgs, extraAgentArgs)).absolutePath,
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