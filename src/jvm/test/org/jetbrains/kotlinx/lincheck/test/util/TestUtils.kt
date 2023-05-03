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

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.appendFailure
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.LTS
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.lang.reflect.Method

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

internal fun assertScenariosEquals(expected: ExecutionScenario, actual: ExecutionScenario) {
    assertActorsSequenceEquals(expected.initExecution, actual.initExecution)

    assertEquals(expected.parallelExecution.size, actual.parallelExecution.size)
    expected.parallelExecution.zip(actual.parallelExecution).forEach { (expectedThreadActors, actualThreadActors) ->
        assertActorsSequenceEquals(expectedThreadActors, actualThreadActors)
    }

    assertActorsSequenceEquals(expected.postExecution, actual.postExecution)
}

private fun assertActorsSequenceEquals(expected: List<Actor>, actual: List<Actor>) {
    assertEquals(expected.size, actual.size)

    expected.zip(actual).forEach { (expectedActor, actualActor) ->
        assertActorsEquals(expectedActor, actualActor)
    }
}

private fun assertActorsEquals(expected: Actor, actual: Actor) {
    assertEquals(expected, actual)

    val operationAnnotation = expected.method.getAnnotation(Operation::class.java) ?: return

    assertEquals(operationAnnotation.cancellableOnSuspension, actual.cancelOnSuspension)
    assertEquals(operationAnnotation.allowExtraSuspension, actual.allowExtraSuspension)
    assertEquals(operationAnnotation.blocking, actual.blocking)
    assertEquals(operationAnnotation.causesBlocking, actual.causesBlocking)
    assertEquals(operationAnnotation.promptCancellation, actual.promptCancellation)
}

/**
 * Convenient method to call from Kotlin to avoid passing class object directly
 */
internal fun Any.getMethod(methodName: String, parameterCount: Int) =
    getMethod(this::class.java, methodName, parameterCount)

/**
 * Convenient method to call from Kotlin to avoid passing class object directly
 */
internal fun Any.getSuspendMethod(methodName: String, parameterCount: Int) =
    getMethod(this::class.java, methodName, parameterCount + 1) // + 1 because of continuation parameter

internal fun getMethod(clazz: Class<*>, methodName: String, parameterCount: Int): Method {
    return clazz.declaredMethods.find { it.name == methodName && it.parameterCount == parameterCount }
        ?: error("Method with name $methodName and parameterCount: $parameterCount not found")
}