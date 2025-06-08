/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.util

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator
import org.jetbrains.kotlinx.lincheck.traceagent.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.util.DEFAULT_TEST_JDK_VERSION
import org.jetbrains.kotlinx.lincheck.util.JdkVersion
import org.jetbrains.kotlinx.lincheck.util.jdkVersion
import org.junit.Assert.*
import java.io.File

/**
 * Checks output when Lincheck run fails with an exception and don't return [LincheckFailure]
 * internally.
 * This happens when the configuration of the test is incorrect.
 * @param expectedOutputFile name of file stored in resources/expected_logs, storing the expected lincheck output.
 */
internal inline fun <reified E: Exception> Options<*, *>.checkFailsWithException(testClass: Class<*>, expectedOutputFilePrefix: String) {
    try {
        LinChecker(testClass, this).check()
    } catch (e: Exception) {
        assertTrue(
            "Exception of type ${E::class.simpleName} expected, but ${e::class.simpleName} was thrown.\n $e",
            e is E
        )
        val actualOutput = e.message ?: ""
        compareAndOverwrite(expectedOutputFilePrefix, actualOutput)
    }
}

/**
 * Checks that failure output matches the expected one stored in a file.
 *
 * @param expectedOutputFilePrefix name of file stored in resources/expected_logs, storing the expected lincheck output.
 */
internal fun LincheckFailure?.checkLincheckOutput(expectedOutputFilePrefix: String) {
    check(this != null) { "The test should fail" }
    val actualOutput = StringBuilder().appendFailure(this).toString()
    compareAndOverwrite(expectedOutputFilePrefix, actualOutput)
}

/**
 * Compares actual output to expected output on file.
 * If needed and in [OVERWRITE_REPRESENTATION_TESTS_OUTPUT] mode, overwrite.
 */
private fun compareAndOverwrite(expectedOutputFilePrefix: String, actualOutput: String) {
    check(!expectedOutputFilePrefix.contains(".txt")) {
        "Filename $expectedOutputFilePrefix should not contain a file extension (.txt)"
    }

    val expectedOutputFile = getExpectedLogFile(expectedOutputFilePrefix)
    val expectedOutput = expectedOutputFile?.readText()

    if (actualOutput.filtered != expectedOutput?.filtered) {
        if (OVERWRITE_REPRESENTATION_TESTS_OUTPUT &&
            // overwrite if the expected log already exists, or we are in the default configuration
            (expectedOutputFile != null || isDefaultConfiguration(jdkVersion, isInTraceDebuggerMode))
        ) {
            val overwriteOutputFileName = generateExpectedLogFileName(
                fileNamePrefix = expectedOutputFilePrefix,
                jdkVersion = jdkVersion,
                inTraceDebuggerMode = isInTraceDebuggerMode,
            )
            val overwriteOutputFile = getExpectedLogFileFromSources(overwriteOutputFileName)
            overwriteOutputFile.writeText(actualOutput)
            return
        }

        if (expectedOutputFile == null) {
            error(
                """
                No file exists yet for the test $expectedOutputFilePrefix. 
                    JDK version = $jdkVersion;
                    Trace debugger mode = ${isInTraceDebuggerMode}.
                """
                .trimIndent()
            )
        }

        assertEquals(expectedOutput, actualOutput)
    }
}

/* To prevent file duplication, this function finds the file to compare the results to.
 * It first tries to find a jdk- and mode-specific file.
 * If there is no such file, it then looks up for a default file
 * (i.e., file for a default JDK version and non-trace mode).
 *
 * For instance, we are running tests for jdk 11 (trace mode),
 * we will check file existence in the following order:
 *   fn_trace_debugger_jdk11.txt
 *   fn_jdk11.txt
 *   fn_trace_debugger.txt
 *   fn.txt
 *
 * Returns null if none of these files exists.
 */
private fun getExpectedLogFile(expectedOutputFilePrefix: String): File? {
    val testConfigurations = listOfNotNull(
        // first try to pick the most specific file if it exists
        TestFileConfiguration(
            jdkVersion = jdkVersion,
            inTraceDebuggerMode = isInTraceDebuggerMode
        ),

        // next, try to pick the jdk-specific file
        TestFileConfiguration(
            jdkVersion = jdkVersion,
            inTraceDebuggerMode = false
        ),

        // if in trace-debugger mode, try to pick the trace-debugger-specific file
        if (isInTraceDebuggerMode)
            TestFileConfiguration(
                jdkVersion = DEFAULT_TEST_JDK_VERSION,
                inTraceDebuggerMode = true
            )
        else null,

        // finally, try the default file
        TestFileConfiguration(
            jdkVersion = DEFAULT_TEST_JDK_VERSION,
            inTraceDebuggerMode = false
        )
    )

    for (testConfiguration in testConfigurations) {
        val fileName = generateExpectedLogFileName(
            fileNamePrefix = expectedOutputFilePrefix,
            jdkVersion = testConfiguration.jdkVersion,
            inTraceDebuggerMode = testConfiguration.inTraceDebuggerMode,
        )
        val file = getExpectedLogFileFromResources(fileName)
        if (file != null) return file
    }

    return null
}

internal fun getExpectedLogFileFromResources(fullFileName: String): File? =
    ClassLoader.getSystemResource("expected_logs/$fullFileName")?.file?.let { File(it) }

internal fun getExpectedLogFileFromSources(fullFileName: String): File =
    File("src/jvm/test/resources/expected_logs/$fullFileName")

// Generates a file name in one of the following forms:
// prefix.txt, prefix_jdk15.txt, prefix_trace_debugger.txt, prefix_trace_debugger_jdk15.txt
private fun generateExpectedLogFileName(
    fileNamePrefix: String,
    jdkVersion: JdkVersion,
    inTraceDebuggerMode: Boolean
): String {
    val traceDebuggerSuffix = if (inTraceDebuggerMode) "_trace_debugger" else ""
    val jdkSuffix = if (jdkVersion != DEFAULT_TEST_JDK_VERSION) "_${jdkVersion}" else ""
    return "${fileNamePrefix}${traceDebuggerSuffix}${jdkSuffix}.txt"
}

private data class TestFileConfiguration(
    val jdkVersion: JdkVersion,
    val inTraceDebuggerMode: Boolean
)

private fun isDefaultConfiguration(jdkVersion: JdkVersion, inTraceDebuggerMode: Boolean) =
    (jdkVersion == DEFAULT_TEST_JDK_VERSION) && !inTraceDebuggerMode

private val String.filtered: String get() {
    // Remove platform-specific lines
    var filtered = lines().filter {
        !it.matches(TEST_EXECUTION_TRACE_ELEMENT_REGEX)
    }.joinToString("\n")
    // Remove line numbers
    filtered = filtered.replace(LINE_NUMBER_REGEX, "")
    // Remove trailing spaces
    filtered = filtered.replace(TRAILING_STACKTRACE_SPACES, " |")
    // Remove repeating hyphens
    filtered = filtered.replace(REPEATING_HYPHENS, " - ")
    return filtered
}

// removing the following lines from the trace (because they may vary):
// - pattern `org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution(\d+)` (the number of thread may vary)
// - everything from `java.base/java.lang` (because code locations may vary between different versions of JVM)
private val TEST_EXECUTION_TRACE_ELEMENT_REGEX = listOf(
    "(\\W*)at org\\.jetbrains\\.kotlinx\\.lincheck\\.runner\\.TestThreadExecution(\\d+)\\.run\\(Unknown Source\\)",
    "(\\W*)at org\\.jetbrains\\.kotlinx\\.lincheck\\.runner\\.FixedActiveThreadsExecutor\\.testThreadRunnable\\\$lambda\\\$(\\d+)\\(FixedActiveThreadsExecutor.kt:(\\d+)\\)",
    "(\\W*)at (java.base\\/)?java.lang(.*)"
).joinToString(separator = ")|(", prefix = "(", postfix = ")").toRegex()

private val LINE_NUMBER_REGEX = Regex(":(\\d+)")
private val TRAILING_STACKTRACE_SPACES = Regex(" +\\|")
private val REPEATING_HYPHENS = Regex(" -+ ")

fun checkTraceHasNoLincheckEvents(trace: String) {
    val testPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck_test.").size - 1
    val lincheckPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck.").size - 1
    check(testPackageOccurrences == lincheckPackageOccurrences) { "Internal Lincheck events were found in the trace" }
}

fun checkFailureIsNotLincheckInternalBug(failure: LincheckFailure) {
    check("You've caught a bug in Lincheck." !in failure.toString()) {
        "Internal Lincheck bug was detected\n$failure"
    }
}

/**
 * A generator for producing random strings from a predefined pool of constants.
 */
@Suppress("UNUSED_PARAMETER")
class StringPoolGenerator(randomProvider: RandomProvider, configuration: String): ParameterGenerator<String> {
    private val random = randomProvider.createRandom()

    // TODO: this generator can be generalized to a generator choosing random element
    //   from an arbitrary user-defined list
    private val strings = arrayOf("", "abc", "xyz")

    override fun generate(): String =
        strings[random.nextInt(strings.size)]
}

internal val OVERWRITE_REPRESENTATION_TESTS_OUTPUT: Boolean =
    System.getProperty("lincheck.overwriteRepresentationTestsOutput").toBoolean()
