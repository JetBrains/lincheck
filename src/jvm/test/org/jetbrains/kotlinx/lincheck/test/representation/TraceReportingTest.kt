/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
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
        check("canEnterForbiddenSection.WRITE(true) at TraceReportingTest.resetFlag(TraceReportingTest.kt:65)" in log)
        check("canEnterForbiddenSection.WRITE(false) at TraceReportingTest.resetFlag(TraceReportingTest.kt:66)" in log)
        check("a.READ: 0 at TraceReportingTest.bar" in log)
        check("a.WRITE(1) at TraceReportingTest.bar" in log)
        check("a.READ: 1 at TraceReportingTest.bar" in log)
        check("a.WRITE(2) at TraceReportingTest.bar" in log)
        check("MONITORENTER at TraceReportingTest.resetFlag" in log)
        check("MONITOREXIT at TraceReportingTest.resetFlag" in log)
        checkTraceHasNoLincheckEvents(log)
    }
}
