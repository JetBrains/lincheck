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

internal fun Any.runModelCheckingTestAndCheckOutput(
    expectedLogsFile: String,
    testConfiguration: ModelCheckingOptions.() -> Unit
) {
    val modelCheckingOptions = ModelCheckingOptions()
    testConfiguration(modelCheckingOptions)
    val failure = modelCheckingOptions.checkImpl(this::class.java)

    check(failure != null) { "The test should fail" }

    val actualLogLines = StringBuilder().appendFailure(failure).toString().lines()
    val expectedLogLines = getExpectedLogFromResources(expectedLogsFile)

    expectedLogLines.zip(actualLogLines).forEachIndexed { index, (expectedLine, actualLine) ->
        assertEquals("Expected output doesn't match actual at line number: ${index + 1}", expectedLine, actualLine)
    }

    assertEquals("Expected log size doesn't match actual", expectedLogLines.size, actualLogLines.size)
}

private fun Any.getExpectedLogFromResources(testFileName: String): List<String> {
    val resourceName = "expected_logs/$testFileName"
    val expectedLogResource = this::class.java.classLoader.getResourceAsStream(resourceName)
        ?: error("Expected log resource: $resourceName does not exist")

    return expectedLogResource.reader().readLines()
}
