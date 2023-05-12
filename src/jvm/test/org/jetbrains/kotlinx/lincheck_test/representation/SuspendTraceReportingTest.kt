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

import kotlinx.coroutines.sync.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck_test.util.runModelCheckingTestAndCheckOutput
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*


class SuspendTraceReportingTest : VerifierState() {
    private val lock = Mutex()
    private var canEnterForbiddenBlock: Boolean = false
    private var barStarted: Boolean = false
    private var counter: Int = 0

    @Operation(allowExtraSuspension = true, cancellableOnSuspension = false)
    suspend fun foo() {
        if (barStarted) canEnterForbiddenBlock = true
        lock.withLock {
            counter++
        }
        canEnterForbiddenBlock = false
    }

    @Operation(allowExtraSuspension = true, cancellableOnSuspension = false)
    suspend fun bar(): Int {
        barStarted = true
        lock.withLock {
            counter++
        }
        if (canEnterForbiddenBlock) return -1
        return 0
    }

    override fun extractState(): Any = counter

    @Test
    fun test() = runModelCheckingTestAndCheckOutput("suspend_trace_reporting.txt") {
        actorsPerThread(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}