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
