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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.test.*
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
    fun test() {
        val failure = ModelCheckingOptions()
            .actorsAfter(0)
            .actorsBefore(0)
            .actorsPerThread(1)
            .checkImpl(this::class.java)
        checkNotNull(failure) { "test should fail" }
        val log = failure.toString()
        check("foo" in log)
        check("canEnterForbiddenSection.WRITE(true) at TraceReportingTest.resetFlag(TraceReportingTest.kt:" in log)
        check("canEnterForbiddenSection.WRITE(false) at TraceReportingTest.resetFlag(TraceReportingTest.kt:" in log)
        check("a.READ: 0 at TraceReportingTest.bar" in log)
        check("a.WRITE(1) at TraceReportingTest.bar" in log)
        check("a.READ: 1 at TraceReportingTest.bar" in log)
        check("a.WRITE(2) at TraceReportingTest.bar" in log)
        check("MONITORENTER at TraceReportingTest.resetFlag" in log)
        check("MONITOREXIT at TraceReportingTest.resetFlag" in log)
        checkTraceHasNoLincheckEvents(log)
    }
}
