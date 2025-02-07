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
import org.junit.Assert
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*

abstract class AbstractTraceDebuggerIntegrationTest {
    companion object {
        private val OS: String = System.getProperty("os.name").lowercase(Locale.getDefault())

        private fun isWindows(): Boolean = OS.contains("win")
    }

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
                        options.jvmArgs = jvmArgs
                    }
                }
            }
        """.trimIndent()
    }

    private fun getGolderDataPathFor(testClassName: String, testMethodName: String): String {
        val projectName = File(projectPath).name
        return File("").absolutePath + "/src/jvm/integrationTestData/$projectName/${testClassName}_$testMethodName.txt"
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
        val processBuilder = ProcessBuilder(
            if (isWindows()) "gradlew.bat" else "./gradlew",
            *gradleCommands.toTypedArray(),
            "--tests",
            "$testClassName.$testMethodName",
            "--init-script",
            createInitScriptAsTempFile(buildGradleInitScriptToDumpTrace(testClassName, testMethodName, tmpFile)).absolutePath,
        )
        processBuilder.directory(File(projectPath))

        val process = processBuilder.start()
        process.errorStream.copyTo(System.err)
        process.waitFor().let {
            if (it != 0) error("Gradle tests failed with exit code $it")
        }

        // TODO decide how to test: with gold data or run twice?
        val expectedOutput = File(getGolderDataPathFor(testClassName, testMethodName))
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
