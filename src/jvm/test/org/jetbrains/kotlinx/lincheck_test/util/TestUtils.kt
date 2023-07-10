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
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import java.lang.reflect.Method

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
        assertEquals(actualOutput, expectedOutput)
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