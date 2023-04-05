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
import org.junit.Assert.*
import java.io.*

internal fun verifyOutput(testName: String) {
    val classLoader = LinChecker::class.java.classLoader
    classLoader.getResourceAsStream("output/$testName").use {
        // TODO: see StackTraceRecoveryChannelsTest in KotlinCoroutines and verifyStackTrace in Stacktraces.kt
//        public fun verifyStackTrace(path: String, e: Throwable) {
//            val resource = Job::class.java.classLoader.getResourceAsStream("stacktraces/$path.txt")
//            val lines = resource.reader().readLines()
//            verifyStackTrace(e, *lines.toTypedArray())
//        }
    }

}

internal fun Any.runModelCheckingTestAndCheckOutput(testName: String, testConfiguration: ModelCheckingOptions.() -> Unit) {
    // TODO: implement me
}

private const val TEST_RESOURCES_EXPECTED_OUTPUT_PATH = "src/jvm/test/resources/output"
internal fun Any.lincheckOutputTest(
    options: ModelCheckingOptions,
    expectedLogFileName: String
) {
    val testClass = this::class.java
    val failure = options.checkImpl(testClass)

    check(failure != null) { "the test should fail" }

    val log = StringBuilder().appendFailure(failure).toString()
    val expectedFullFileName = TEST_RESOURCES_EXPECTED_OUTPUT_PATH + File.separator + expectedLogFileName
    val expectedLogFile = File(expectedFullFileName)

    if (!expectedLogFile.exists()) {
        fail("Supplied file: $expectedFullFileName does not exist")
    }
    val expectedLogLines = expectedLogFile.readLines()
    val actualLogLines = log.lines()

    expectedLogLines.zip(actualLogLines).forEachIndexed { index, (expectedLine, actualLine) ->
        assertEquals("Expected output doesn't match actual at line number: ${index + 1}", expectedLine, actualLine)
    }

    assertEquals("Expected log size doesn't match actual", expectedLogLines.size, actualLogLines.size)
}