/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.util.InternalLincheckExceptionEmulator.throwException
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.Test

/**
 * This test checks that if exception is thrown from the Lincheck itself, it will be reported properly.
 * Bug exception is emulated using [org.jetbrains.kotlinx.lincheck.util.InternalLincheckExceptionEmulator],
 * which is located in org.jetbrains.kotlinx.lincheck package, so exception will be treated like internal bug.
 */
@Suppress("UNUSED")
class InternalLincheckBugTest {

    private var canEnterForbiddenSection = false

    @Operation
    fun operation1() {
        canEnterForbiddenSection = true
        canEnterForbiddenSection = false
    }

    @Operation
    fun operation2() {
        if (canEnterForbiddenSection) throwException()
    }

    @Test
    fun `should add stackTrace to output`() = runModelCheckingTestAndCheckOutput(
        expectedOutputFile = "internal_bug_report.txt",
        // removing lines of pattern org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution(\d+)
        // as its number may vary
        linesToRemoveRegex = TEST_EXECUTION_TRACE_ELEMENT_REGEX,
    ) {
        actorsPerThread(2)
    }
}