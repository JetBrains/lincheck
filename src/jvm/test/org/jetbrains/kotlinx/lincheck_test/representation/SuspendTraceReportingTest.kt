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

import kotlinx.coroutines.sync.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*


class SuspendTraceReportingTest {
    private val lock = Mutex()
    private var canEnterForbiddenBlock: Boolean = false
    private var barStarted: Boolean = false
    private var counter: Int = 0

    @Operation(cancellableOnSuspension = false)
    suspend fun foo() {
        if (barStarted) canEnterForbiddenBlock = true
        lock.withLock {
            counter++
        }
        canEnterForbiddenBlock = false
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun bar(): Int {
        barStarted = true
        lock.withLock {
            counter++
        }
        if (canEnterForbiddenBlock) return -1
        return 0
    }

    @Test
    fun test() = ModelCheckingOptions().apply {
        actorsPerThread(1)
        actorsBefore(0)
        actorsAfter(0)
    }
        .checkImpl(this::class.java)
        .checkLincheckOutput("suspend_trace_reporting.txt")

}