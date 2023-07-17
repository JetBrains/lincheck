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
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.Assert.*

/**
 * Checks that failure output matches the expected one stored in a file.
 *
 * @param expectedOutputFile name of file stored in resources/expected_logs, storing the expected lincheck output.
 */
internal fun LincheckFailure?.checkLincheckOutput(expectedOutputFile: String) {
    check(this != null) { "The test should fail" }

    val actualOutput = StringBuilder().appendFailure(this).toString()
    val expectedOutput = getExpectedLogFromResources(expectedOutputFile)

    if (actualOutput.filtered != expectedOutput.filtered) {
        assertEquals(expectedOutput, actualOutput)
    }
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
// - everything from `java.base/` (because code locations may vary between different versions of JVM)
private val TEST_EXECUTION_TRACE_ELEMENT_REGEX = listOf(
    "(\\W*)at org\\.jetbrains\\.kotlinx\\.lincheck\\.runner\\.TestThreadExecution(\\d+)\\.run\\(Unknown Source\\)",
    "(\\W*)at java.base\\/(.*)"
).joinToString(separator = ")|(", prefix = "(", postfix = ")").toRegex()

private val LINE_NUMBER_REGEX = Regex(":(\\d+)\\)")

internal fun getExpectedLogFromResources(testFileName: String): String {
    val resourceName = "expected_logs/$testFileName"
    val expectedLogResource = LTS::class.java.classLoader.getResourceAsStream(resourceName)
        ?: error("Expected log resource: $resourceName does not exist")

    return expectedLogResource.reader().readText()
}

fun checkTraceHasNoLincheckEvents(trace: String) {
    val testPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck_test.").size - 1
    val lincheckPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck.").size - 1
    check(testPackageOccurrences == lincheckPackageOccurrences) { "Internal Lincheck events were found in the trace" }
}