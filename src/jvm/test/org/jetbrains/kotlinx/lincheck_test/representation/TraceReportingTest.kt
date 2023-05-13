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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck_test.*
import org.jetbrains.kotlinx.lincheck_test.util.runModelCheckingTestAndCheckOutput
import org.junit.*

/**
 * This test checks basic interleaving reporting features,
 * including reporting of lock acquiring/releasing, reads/writes with parameter/result capturing.
 */
class TraceReportingTest {
    @Volatile
    var a = 0
    @Volatile
    var b = 0
    @Volatile
    var canEnterForbiddenSection = false

    @Operation
    fun foo(): Int {
        if (canEnterForbiddenSection) {
            return 1
        }
        return 0
    }

    @Operation
    fun bar() {
        repeat(2) {
            a++
        }
        uselessIncrements(2)
        intermediateMethod()
    }

    private fun intermediateMethod() {
        resetFlag()
    }

    @Synchronized
    private fun resetFlag() {
        canEnterForbiddenSection = true
        canEnterForbiddenSection = false
    }

    private fun uselessIncrements(count: Int): Boolean {
        repeat(count) {
            b++
        }
        return false
    }

    @Test
    fun test() = runModelCheckingTestAndCheckOutput("trace_reporting.txt") {
        actorsAfter(0)
        actorsBefore(0)
        actorsPerThread(1)
    }
}
