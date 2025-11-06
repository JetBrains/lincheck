/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.jetbrains.kotlinx.lincheck_test.util.OVERWRITE_REPRESENTATION_TESTS_OUTPUT
import org.junit.After
import org.junit.Assert
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue

abstract class AbstractTraceIntegrationTest {
    abstract val fatJarName: String
    abstract val projectPath: String

    private fun buildGradleInitScriptToDumpTrace(
        gradleCommands: List<String>,
        testClassName: String,
        testMethodName: String,
        fileToDump: File,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
    ): String {
        fun String.escapeDollar() = replace("$", "\\$")

        val pathToFatJar = File(Paths.get("build", "libs", fatJarName).toString()).absolutePath.escape()
        // We need to escape it twice, as our argument parser will de-escape it when split into array
        val pathToOutput = fileToDump.absolutePath.escape().escape()
        val agentArgs =
            "class=${testClassName.escapeDollar()},method=${testMethodName.escapeDollar()},output=${pathToOutput.escapeDollar()}" +
                    extraAgentArgs.entries
                        .joinToString(",") { "${it.key}=${it.value.escapeDollar()}" }
                        .let { if (it.isNotEmpty()) ",$it" else it }
        return """
            gradle.taskGraph.whenReady {
                val gradleCommands = listOf(${gradleCommands.joinToString(",") { "\"$it\"" }})
                val jvmTasks = allTasks.filter { task -> task is JavaForkOptions && gradleCommands.contains(task.path) }
                jvmTasks.forEach { task ->
                    task.doFirst {
                        val options = task as JavaForkOptions
                        val jvmArgs = options.jvmArgs?.toMutableList() ?: mutableListOf()
                        jvmArgs.addAll(listOf(${extraJvmArgs.joinToString(", ") { "\"$it\"" }}))
                        jvmArgs.add("-javaagent:$pathToFatJar=$agentArgs")
                        // We need to append our agent args the latest, so it comes
                        // after any user-specified args (including other agents).
                        // The only reliable way to do this is via `jvmArgumentProviders`
                        options.jvmArgumentProviders.add(CommandLineArgumentProvider { jvmArgs })
                    }
                }
            }
        """.trimIndent()
    }

    private fun getGoldenDataFileFor(
        testClassName: String,
        testMethodName: String,
        testNameSuffix: String? = null
    ): File {
        val projectName = File(projectPath).name
        val fileName = "${testClassName}_$testMethodName${testNameSuffix?.let { "_$it" }.orEmpty()}.txt"
        return File(Paths.get("src", "main", "resources", "integrationTestData", projectName, fileName).toString())
    }

    private fun createInitScriptAsTempFile(content: String): File {
        val tempFile = File.createTempFile("initScript", ".gradle.kts")
        tempFile.deleteOnExit()
        tempFile.writeText(content)
        return tempFile
    }

    val failOnErrorInStdErr: (String) -> Unit = {
        if (it.lines().any { line -> line.startsWith("[ERROR] ") }) {
            Assert.fail("Error output in stderr:\n$it")
        }
    }

    protected open fun runTest(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String> = emptyList(),
        extraAgentArgs: Map<String, String> = emptyMap(),
        commands: List<String>,
        checkRepresentation: Boolean = true,
        testNameSuffix: String? = null,
        onStdErrOutput: (String) -> Unit = failOnErrorInStdErr,
    ) {
        val (_, output) = withStdErrTee {
            runTestImpl(
                testClassName,
                testMethodName,
                extraJvmArgs,
                extraAgentArgs,
                commands,
                checkRepresentation,
                testNameSuffix,
            )
        }
        onStdErrOutput(output)
    }

    // TODO: rewrite to accept array of tests (or TestSuite maybe better)
    private fun runTestImpl(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String> = emptyList(),
        extraAgentArgs: Map<String, String> = emptyMap(),
        commands: List<String>,
        checkRepresentation: Boolean = true,
        testNameSuffix: String? = null,
    ) {
        val tmpFile = File.createTempFile(testClassName + "_" + testMethodName, "")
        val packedTraceFile = File("${tmpFile.absolutePath}.packedtrace")
        val indexFile = File("${tmpFile.absolutePath}.idx")

        taskQueue.add {
            tmpFile.delete()
            packedTraceFile.delete()
            indexFile.delete()
        }

        runGradleTest(testClassName, testMethodName, extraJvmArgs, extraAgentArgs, commands, tmpFile)

        // TODO decide how to test: with gold data or run twice?
        if (checkRepresentation) { // otherwise we just want to make sure that tests do not fail
            val expectedOutput = getGoldenDataFileFor(testClassName, testMethodName, testNameSuffix)
            if (expectedOutput.exists()) {
                Assert.assertEquals(expectedOutput.readText(), tmpFile.readText())
            } else {
                if (OVERWRITE_REPRESENTATION_TESTS_OUTPUT) {
                    expectedOutput.parentFile.mkdirs()
                    copy(tmpFile, expectedOutput)
                    Assert.fail("The gold data file was created. Please rerun the test.")
                } else {
                    Assert.fail(
                        "The gold data file was not found at '${expectedOutput.absolutePath}'. " +
                                "Please rerun the test with \"overwriteRepresentationTestsOutput\" option enabled."
                    )
                }
            }
        } else {
            fun checkNonEmptyNess(file: File, filePurpose: String = "output") {
                val fileContainsContent = file.bufferedReader().lineSequence().any { it.isNotEmpty() }
                if (!fileContainsContent) {
                    Assert.fail("Empty $filePurpose file was produced by the test: $file.")
                }
            }

            if (tmpFile.exists()) {
                checkNonEmptyNess(tmpFile)
            } else {
                if (packedTraceFile.exists()) {
                    checkNonEmptyNess(packedTraceFile)
                } else {
                    Assert.fail("No output was produced by the test.")
                }
            }
        }
    }

    private fun runGradleTest(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
        gradleCommands: List<String>,
        outputFile: File
    ) {
        createGradleConnection().use { connection ->
            connection
                .newBuild()
                .setStandardError(System.err)
                .addArguments(
                    "-Dorg.gradle.daemon=false",
                    "--init-script",
                    createInitScriptAsTempFile(
                        buildGradleInitScriptToDumpTrace(
                            gradleCommands, testClassName, testMethodName, outputFile, extraJvmArgs, extraAgentArgs
                        )
                    ).absolutePath,
                ).forTasks(
                    *gradleCommands.toTypedArray(),
                    "--tests",
                    "$testClassName.$testMethodName",
                ).run()
        }
    }

    private val taskQueue = ConcurrentLinkedQueue<() -> Unit>()

    @After
    fun tearDown() {
        while (true) {
            taskQueue.poll()?.invoke() ?: break
        }
    }

    private fun copy(srcFile: File, destFile: File) {
        val src = FileInputStream(srcFile).getChannel()
        val dest = FileOutputStream(destFile).getChannel()
        dest.transferFrom(src, 0, src.size())
    }

    /**
     * Creates a new gradle connection to the project from [projectPath].
     */
    private fun createGradleConnection(): ProjectConnection = GradleConnector
        .newConnector()
        .forProjectDirectory(File(projectPath))
        .connect()
}

private fun String.escape(): String = this.replace("\\", "\\\\")
