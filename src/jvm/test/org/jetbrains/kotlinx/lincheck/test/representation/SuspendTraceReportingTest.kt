/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.representation

import kotlinx.coroutines.sync.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.test.util.runModelCheckingTestAndCheckOutput
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