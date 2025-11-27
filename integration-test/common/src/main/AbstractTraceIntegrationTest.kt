/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import org.jetbrains.kotlinx.lincheck_test.util.OVERWRITE_REPRESENTATION_TESTS_OUTPUT
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue

abstract class AbstractTraceIntegrationTest {
    open val fatJarName: String = "trace-recorder-fat.jar"
    
    open val formatArgs: Map<String, String> = mapOf(
        "format" to "text",
        "formatOption" to "verbose",
    )
    
    open val defaultJvmArgs: List<String> = listOf(
        "-Dlincheck.traceRecorderMode=true",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:hashCode=2", // This line is required to make hashCode deterministic. Mode "2" means "use constant as hash code".
    )
    
    abstract val projectPath: String

    protected val pathToFatJar: String
        get() = File(Paths.get("build", "libs", fatJarName).toString()).absolutePath.escape()

    private fun getGoldenDataFileFor(
        testClassName: String,
        testMethodName: String,
        testNameSuffix: String? = null
    ): File {
        val projectName = File(projectPath).name
        val fileName = "${testClassName}_$testMethodName${testNameSuffix?.let { "_$it" }.orEmpty()}.txt"
        return File(Paths.get("src", "main", "resources", "integrationTestData", projectName, fileName).toString())
    }

    private val failOnErrorInStdErr: (String) -> Unit = {
        if (it.lines().any { line -> line.startsWith("[ERROR] ") }) {
            Assertions.fail("Error output in stderr:\n$it")
        }
    }

    protected abstract fun runTestImpl(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String>,
        extraAgentArgs: Map<String, String>,
        commands: List<String>,
        outputFile: File
    )

    protected fun runTest(
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
            runTestAndCompare(
                testClassName,
                testMethodName,
                extraJvmArgs + defaultJvmArgs,
                extraAgentArgs + formatArgs,
                commands,
                checkRepresentation,
                testNameSuffix,
            )
        }
        onStdErrOutput(output)
    }

    // TODO: rewrite to accept array of tests (or TestSuite maybe better)
    private fun runTestAndCompare(
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

        runTestImpl(testClassName, testMethodName, extraJvmArgs, extraAgentArgs, commands, tmpFile)

        compareOutput(checkRepresentation, testClassName, testMethodName, testNameSuffix, tmpFile, packedTraceFile)
    }

    private fun compareOutput(
        checkRepresentation: Boolean,
        testClassName: String,
        testMethodName: String,
        testNameSuffix: String?,
        outputFile: File,
        packedOutputFile: File
    ) {
        // TODO decide how to test: with gold data or run twice?
        if (checkRepresentation) { // otherwise we just want to make sure that tests do not fail
            val expectedOutput = getGoldenDataFileFor(testClassName, testMethodName, testNameSuffix)
            if (expectedOutput.exists()) {
                val expected = expectedOutput.readText()
                val actual = outputFile.readText()
                if (OVERWRITE_REPRESENTATION_TESTS_OUTPUT) {
                    if (expected != actual) {
                        expectedOutput.writeText(actual)
                    }
                } else {
                    Assertions.assertEquals(expected, actual)
                }
            } else {
                if (OVERWRITE_REPRESENTATION_TESTS_OUTPUT) {
                    expectedOutput.parentFile.mkdirs()
                    copy(outputFile, expectedOutput)
                    Assertions.fail("The gold data file was created. Please rerun the test.")
                } else {
                    Assertions.fail(
                        "The gold data file was not found at '${expectedOutput.absolutePath}'. " +
                                "Please rerun the test with \"overwriteRepresentationTestsOutput\" option enabled."
                    )
                }
            }
        } else {
            fun checkNonEmptyNess(file: File, filePurpose: String = "output") {
                val fileContainsContent = file.bufferedReader().lineSequence().any { it.isNotEmpty() }
                if (!fileContainsContent) {
                    Assertions.fail<Unit>("Empty $filePurpose file was produced by the test: $file.")
                }
            }

            if (outputFile.exists()) {
                checkNonEmptyNess(outputFile)
            } else {
                if (packedOutputFile.exists()) {
                    checkNonEmptyNess(packedOutputFile)
                } else {
                    Assertions.fail("No output was produced by the test.")
                }
            }
        }
    }

    private val taskQueue = ConcurrentLinkedQueue<() -> Unit>()

    @AfterEach
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
}
