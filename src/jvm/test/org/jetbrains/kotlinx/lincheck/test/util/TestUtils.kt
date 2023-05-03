/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.test.util

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.LTS
import org.junit.Assert.*

/**
 * Runs lincheck test in model checking mode.
 *
 * Implemented as an extension to Any to avoid passing test class as an argument.
 *
 * @receiver The test instance, its class will be passed to [checkImpl] as testClass.
 * @param expectedOutputFile file name from resources/expected_logs, the expected lincheck output
 * @param testConfiguration options configuration action
 */
internal fun Any.runModelCheckingTestAndCheckOutput(
    expectedOutputFile: String, testConfiguration: ModelCheckingOptions.() -> Unit
) {
    val modelCheckingOptions = ModelCheckingOptions()
    testConfiguration(modelCheckingOptions)
    val failure = modelCheckingOptions.checkImpl(this::class.java)

    check(failure != null) { "The test should fail" }

    val actualOutput = StringBuilder().appendFailure(failure).toString()
    val actualOutputLines = actualOutput.lines()
    val expectedOutput = getExpectedLogFromResources(expectedOutputFile)
    val expectedOutputLines = expectedOutput.lines()

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

private fun getExpectedLogFromResources(testFileName: String): String {
    val resourceName = "expected_logs/$testFileName"
    val expectedLogResource = LTS::class.java.classLoader.getResourceAsStream(resourceName)
        ?: error("Expected log resource: $resourceName does not exist")

    return expectedLogResource.reader().readText()
}
