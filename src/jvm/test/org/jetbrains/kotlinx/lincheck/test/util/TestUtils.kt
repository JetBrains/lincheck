/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
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
