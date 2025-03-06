/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation.gpmc

import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test

abstract class BaseGPMCRepresentationTest<R>(private val outputFileName: String) {
    /**
     * Implement me and place the logic to check its trace.
     */
    abstract fun block(): R

    @Test
    fun test() {
        runConcurrentTestAndCheckOutput(outputFileName, ::block)
    }
}

@OptIn(ExperimentalModelCheckingAPI::class)
fun runConcurrentTestAndCheckOutput(outputFileName: String, block: () -> Unit) {
    val result = runCatching {
        runConcurrentTest(block)
    }
    check(result.isFailure) {
        "The test should fail, but it completed successfully"
    }
    val error = result.exceptionOrNull()!!
    check(error is LincheckAssertionError) {
        """
            |The test should throw LincheckAssertionError, but instead it failed with:
            |${error.stackTraceToString()}
            """
            .trimMargin()
    }
    error.failure.checkLincheckOutput(outputFileName)
}