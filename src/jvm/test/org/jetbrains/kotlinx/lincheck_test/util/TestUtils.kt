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
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.verifier.LTS
import org.junit.Assert.*

/**
 * Checks that failure output matches the expected one stored in a file.
 *
 * @receiver checked failure.
 * @param expectedOutputFile name of file stored in resources/expected_logs, storing the expected lincheck output.
 * @param linesToRemoveRegex regex to filter expected and actual output to remove test suite-dependent lines
 */
internal fun LincheckFailure?.checkLincheckOutput(expectedOutputFile: String, linesToRemoveRegex: Regex? = null) {
    // val modelCheckingOptions = ModelCheckingOptions()
    // testConfiguration(modelCheckingOptions)
    // val failure = modelCheckingOptions.checkImpl(this::class.java)
    check(this != null) { "The test should fail" }

    val actualOutput = StringBuilder().appendFailure(this).toString()
    val actualOutputLines = actualOutput.getLines(linesToRemoveRegex)
    val expectedOutput = getExpectedLogFromResources(expectedOutputFile)
    val expectedOutputLines = expectedOutput.getLines(linesToRemoveRegex)

    expectedOutputLines.zip(actualOutputLines).forEachIndexed { index, (expectedLine, actualLine) ->
        assertValuesEqualsAndPrintAllOutputsIfFailed(
            expectedValue = expectedLine,
            actualValue = actualLine,
            expectedOutput = expectedOutput,
            actualOutput = actualOutput
        ) { "Expected output doesn't match actual at line number: ${index + 1}" }
    }

    assertValuesEqualsAndPrintAllOutputsIfFailed(
        expectedValue = expectedOutputLines.size,
        actualValue = actualOutputLines.size,
        expectedOutput = expectedOutput,
        actualOutput = actualOutput
    ) { "Expected output size doesn't match actual" }
}

private fun String.getLines(filterRegex: Regex?): List<String> {
    return filterRegex?.let {  lines().filter { !it.matches(filterRegex) } } ?: lines()
}

private fun assertValuesEqualsAndPrintAllOutputsIfFailed(
    expectedValue: Any,
    actualValue: Any,
    expectedOutput: String,
    actualOutput: String,
    messageSupplier: () -> String
) {
    if (expectedValue != actualValue) {
        fail(
            // Multiline string is not used here as to .trimIndent function considers lincheck indents and makes ugly output
            buildString {
                appendLine(messageSupplier())

                appendLine()
                appendLine("Expected:")
                appendLine(expectedValue)
                appendLine("Actual:")
                appendLine(actualValue)

                appendLine()
                appendLine("Expected full output:")
                appendLine(expectedOutput)
                appendLine()
                appendLine("Actual full output:")
                appendLine(actualOutput)
            }
        )
    }
}

internal fun getExpectedLogFromResources(testFileName: String): String {
    val resourceName = "expected_logs/$testFileName"
    val expectedLogResource = LTS::class.java.classLoader.getResourceAsStream(resourceName)
        ?: error("Expected log resource: $resourceName does not exist")

    return expectedLogResource.reader().readText()
}

// removing the following lines from the trace (because they may vary):
// - pattern `org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution(\d+)` (the number of thread may vary)
// - everything from `java.base/` (because code locations may vary between different versions of JVM)
internal val TEST_EXECUTION_TRACE_ELEMENT_REGEX = listOf(
    "(\\W*)at org\\.jetbrains\\.kotlinx\\.lincheck\\.runner\\.TestThreadExecution(\\d+)\\.run\\(Unknown Source\\)",
    "(\\W*)at java.base\\/(.*)"
).joinToString(separator = ")|(", prefix = "(", postfix = ")").toRegex()

fun checkTraceHasNoLincheckEvents(trace: String) {
    val testPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck_test.").size - 1
    val lincheckPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck.").size - 1
    check(testPackageOccurrences == lincheckPackageOccurrences) { "Internal Lincheck events were found in the trace" }
}