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
        check(!expectedOutputFilePrefix.contains(".txt")) { 
            "Filename $expectedOutputFilePrefix should not contain a file extension (.txt)"        
        }
        
        assertTrue(
            "Exception of type ${E::class.simpleName} expected, but ${e::class.simpleName} was thrown.\n $e",
            e is E
        )
        val actualOutput = e.message ?: ""
        // Always overwrite jdk8 non-trace
        if (testJdkVersion == TestJdkVersion.JDK_8 && !isInTraceDebuggerMode && OVERWRITE_REPRESENTATION_TESTS_OUTPUT) {
            getExpectedLogFileFromSources(getFileNameFor(expectedOutputFilePrefix, TestJdkVersion.JDK_8, false)).writeText(actualOutput)
            return
        }

        val compareToFile = getFileToCompareTo(expectedOutputFilePrefix)
        val expectedOutput = getExpectedLogFromResources(compareToFile)

        if (actualOutput.filtered != expectedOutput.filtered) {
            if (OVERWRITE_REPRESENTATION_TESTS_OUTPUT) {
                getExpectedLogFileFromSources(getFileNameFor(expectedOutputFilePrefix, testJdkVersion, isInTraceDebuggerMode)).writeText(actualOutput)
            } else {
                assertEquals(expectedOutput, actualOutput)
            }
        }
    }
}

/**
 * Checks that failure output matches the expected one stored in a file.
 *
 * @param expectedOutputFilePrefix name of file stored in resources/expected_logs, storing the expected lincheck output.
 */
internal fun LincheckFailure?.checkLincheckOutput(expectedOutputFilePrefix: String) {
    check(!expectedOutputFilePrefix.contains(".txt")) {
        "Filename $expectedOutputFilePrefix should not contain a file extension (.txt)"
    }
    check(this != null) { "The test should fail" }
    val actualOutput = StringBuilder().appendFailure(this).toString()
    
    // Always overwrite jdk8 non-trace
    if (testJdkVersion == TestJdkVersion.JDK_8 && !isInTraceDebuggerMode && OVERWRITE_REPRESENTATION_TESTS_OUTPUT) {
        getExpectedLogFileFromSources(getFileNameFor(expectedOutputFilePrefix, TestJdkVersion.JDK_8, false)).writeText(actualOutput)
        return
    }
    
    val compareToFile = getFileToCompareTo(expectedOutputFilePrefix)
    val expectedOutput = getExpectedLogFromResources(compareToFile)

    if (actualOutput.filtered != expectedOutput.filtered) {
        if (OVERWRITE_REPRESENTATION_TESTS_OUTPUT) {
            getExpectedLogFileFromSources(getFileNameFor(expectedOutputFilePrefix, testJdkVersion, isInTraceDebuggerMode)).writeText(actualOutput)
        } else {
            assertEquals(expectedOutput, actualOutput)
        }
    }
}

// To prevent file duplication this function finds the file to compare the results to.
// With preference for jdk: 17, 15, 13, 11, 8 (trace mode) and 17, 15, 13, 11, 8 (non trace mode)
// Search starts at current jdk level
// For instance we are running tests for jdk 11 (trace mode), we will check file existence in the following order:
// 11 (trace), 8 (trace), 11 (non-trace) and 8 (non-trace).
private fun getFileToCompareTo(expectedOutputFilePrefix: String): String {
    // If in trace mode first check if a trace debugger file can be found
    if (isInTraceDebuggerMode) {
        for (i in testJdkVersion.ordinal downTo 0) {
            val jdkVersion = TestJdkVersion.entries[i]
            val fileName = getFileNameFor(expectedOutputFilePrefix, jdkVersion, true)
            if (logFileFromResourcesExists(fileName)) return fileName
        }
    }
    // Check lower sdks in non-trace mode
    for (i in testJdkVersion.ordinal downTo 0) {
        val jdkVersion = TestJdkVersion.entries[i]
        val fileName = getFileNameFor(expectedOutputFilePrefix, jdkVersion, false)
        if (logFileFromResourcesExists(fileName)) return fileName
    }
    error("No file exists yet for this test and current jdk = $testJdkVersion, please run on jdk 8 with overwrite enabled")
}

// Generates file name in the form of 
// prefix.txt, prefix_jdk_15.txt, prefix_trace_debugger.txt, prefix_trace_debugger_jdk_15.txt, etc..
private fun getFileNameFor(expectedOutputFilePrefix: String, jdkVersion: TestJdkVersion, traceMode: Boolean): String {
    return "${expectedOutputFilePrefix}${if (traceMode) "_trace_debugger" else ""}${
        if (jdkVersion == TestJdkVersion.JDK_8) "" else "_${jdkVersion}"
    }.txt"
}

private val String.filtered: String get() {
    // Remove platform-specific lines
    var filtered = lines().filter {
        !it.matches(TEST_EXECUTION_TRACE_ELEMENT_REGEX)
    }.joinToString("\n")
    // Remove line numbers
    filtered = filtered.replace(LINE_NUMBER_REGEX, "")
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

internal fun getExpectedLogFromResources(testFileName: String) =
    getExpectedLogFileFromResources(testFileName).readText()

internal fun getExpectedLogFileFromResources(fileName: String): File =
    ClassLoader.getSystemResource("expected_logs/$fileName")?.file?.let { File(it) }
        ?: error("Expected log resource $fileName does not exist")

// Returns true if file exists.
internal fun logFileFromResourcesExists(fileName: String): Boolean =
    runCatching { getExpectedLogFileFromResources(fileName) }.isSuccess

internal fun getExpectedLogFileFromSources(fileName: String): File = 
    File("src/jvm/test/resources/expected_logs/$fileName")

fun checkTraceHasNoLincheckEvents(trace: String) {
    val testPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck_test.").size - 1
    val lincheckPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck.").size - 1
    check(testPackageOccurrences == lincheckPackageOccurrences) { "Internal Lincheck events were found in the trace" }
}

fun checkFailureIsNotLincheckInternalBug(failure: LincheckFailure) {
    check("You've caught a bug in Lincheck." !in failure.toString()) { "Internal Lincheck bug was detected" }
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

/**
 * Represents a set of Java Development Kit (JDK) versions on which the tests are run.
 */
internal enum class TestJdkVersion {
    JDK_8, JDK_11, JDK_13, JDK_15, JDK_17, JDK_19, JDK_20, JDK_21;
    override fun toString(): String {
        return "jdk${name.removePrefix("JDK_").lowercase()}"
    }
}

/**
 * Determines the current JDK version based on the `java.specification.version` system property.
 * If the system property indicates an unsupported JDK version, an error is thrown.
 */
internal val testJdkVersion: TestJdkVersion = run {
    val jdkVersion = System.getProperty("java.specification.version")
    // java.specification.version is "1.x" for Java prior to 8 and "x" for the newer ones
    when {
        jdkVersion.removePrefix("1.") == "8"    -> TestJdkVersion.JDK_8
        jdkVersion == "11"                      -> TestJdkVersion.JDK_11
        jdkVersion == "13"                      -> TestJdkVersion.JDK_13
        jdkVersion == "15"                      -> TestJdkVersion.JDK_15
        jdkVersion == "17"                      -> TestJdkVersion.JDK_17
        jdkVersion == "19"                      -> TestJdkVersion.JDK_19
        jdkVersion == "20"                      -> TestJdkVersion.JDK_20
        jdkVersion == "21"                      -> TestJdkVersion.JDK_21
        else ->
            error("Unsupported JDK version: $jdkVersion")
    }
}

/**
 * Indicates whether the current Java Development Kit (JDK) version is JDK 8.
 */
internal val isJdk8 = (testJdkVersion == TestJdkVersion.JDK_8)
