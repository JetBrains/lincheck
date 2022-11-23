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

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.test.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

// TODO: support AFU/VarHandle/Unsafe for memory tracking and uncomment this test
//class SuspendTraceReportingTest : VerifierState() {
//    private val lock = Mutex()
//    private var canEnterForbiddenBlock: Boolean = false
//    private var barStarted: Boolean = false
//    private var counter: Int = 0
//
//    @Operation(allowExtraSuspension = true, cancellableOnSuspension = false)
//    suspend fun foo() {
//        if (barStarted) canEnterForbiddenBlock = true
//        lock.withLock {
//            counter++
//        }
//        canEnterForbiddenBlock = false
//    }
//
//    @Operation(allowExtraSuspension = true, cancellableOnSuspension = false)
//    suspend fun bar(): Int {
//        barStarted = true
//        lock.withLock {
//            counter++
//        }
//        if (canEnterForbiddenBlock) return -1
//        return 0
//    }
//
//    override fun extractState(): Any = counter
//
//    @Test
//    fun test() {
//        val failure = ModelCheckingOptions()
//            .actorsPerThread(1)
//            .actorsBefore(0)
//            .actorsAfter(0)
//            .checkImpl(this::class.java)
//        checkNotNull(failure) { "the test should fail" }
//        val log = failure.toString()
//        check("label" !in log) { "suspend state machine related fields should not be reported" }
//        check("L$0" !in log) { "suspend state machine related fields should not be reported" }
//        check(log.numberOfOccurrences("foo()") == 2) {
//            "suspended function should be mentioned exactly twice (once in parallel and once in parallel execution)"
//        }
//        check("barStarted.READ: true" in log) { "this code location after suspension should be reported" }
//        checkTraceHasNoLincheckEvents(log)
//    }
//
//    private fun String.numberOfOccurrences(text: String): Int = split(text).size - 1
//}