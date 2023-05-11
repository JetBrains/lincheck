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

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.appendFailure
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.LTS
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.File
import java.lang.reflect.Method
import java.util.function.Consumer

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

/**
 * Serves for the same purpose as a function below, but is more convenient to call from java
 */
internal fun runModelCheckingTestAndCheckOutput(
    testInstance: Any,
    expectedOutputFile: String,
    testConfiguration: Consumer<ModelCheckingOptions>
) = testInstance.runModelCheckingTestAndCheckOutput(expectedOutputFile) { testConfiguration.accept(this) }

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