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

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.*
import kotlin.coroutines.*
import kotlinx.coroutines.sync.*

// TODO investigate difference for trace debugger (Evgeniy Moiseenko)
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
        addCustomScenario {
            parallel {
                thread { actor(::foo) }
                thread { actor(::bar) }
            }
        }
    }
        .checkImpl(this::class.java)
        .checkLincheckOutput("suspend_trace_reporting" )
}

/* This test checks the trace reporting in case when a nested sequence of `suspend` functions is resumed ---
 * upon the resumption the stack trace should be correctly restored.
 */
class SuspendTraceResumptionReportingTest {
    private var canEnterForbiddenBlock: Boolean = false
    private var barStarted: Boolean = false
    private var counter: Int = 0
    private var continuation: Continuation<Unit>? = null

    @Operation(cancellableOnSuspension = false)
    suspend fun foo() {
        if (barStarted) canEnterForbiddenBlock = true
        function1()
        canEnterForbiddenBlock = false
    }

    @Operation
    fun bar(): Int {
        barStarted = true
        if (continuation != null) {
            continuation!!.resume(Unit)
            if (canEnterForbiddenBlock) return -1
        }
        return 0
    }

    private suspend fun function1() {
        counter++
        function2()
        counter++
    }

    private suspend fun function2() {
        counter++
        function3()
        counter++
    }

    private suspend fun function3() {
        counter++
        suspendCoroutine<Unit> { continuation ->
            this.continuation = continuation
        }
        counter++
    }

    @Test
    fun test() = ModelCheckingOptions().apply {
        addCustomScenario {
            parallel {
                thread { actor(::foo) }
                thread { actor(::bar) }
            }
        }
    }
        .checkImpl(this::class.java)
        .checkLincheckOutput("suspend_trace_resumption_reporting_test")
}

/* This test checks the trace reporting in case when a nested sequence of `suspend` functions is resumed ---
 * upon the resumption the stack trace should be correctly restored.
 *
 * The difference w.r.t `SuspendTraceResumptionReportingTest` is that this test
 * checks the case when some of the suspended functions do not have the resumption part,
 * and thus their stack frames should be omitted in the resumption trace.
 */
class SuspendTraceResumptionFrameSkippingReportingTest {
    private var canEnterForbiddenBlock: Boolean = false
    private var barStarted: Boolean = false
    private var counter: Int = 0
    private var continuation: Continuation<Unit>? = null

    @Operation(cancellableOnSuspension = false)
    suspend fun foo() {
        if (barStarted) canEnterForbiddenBlock = true
        function1()
        canEnterForbiddenBlock = false
    }

    @Operation
    fun bar(): Int {
        barStarted = true
        if (continuation != null) {
            continuation!!.resume(Unit)
            if (canEnterForbiddenBlock) return -1
        }
        return 0
    }

    private suspend fun function1() {
        counter++
        function2()
        counter++
    }

    private suspend fun function2() {
        counter++
        function3()
    }

    private suspend fun function3() {
        counter++
        suspendCoroutine<Unit> { continuation ->
            this.continuation = continuation
        }
    }

    @Test
    fun test() = ModelCheckingOptions().apply {
        addCustomScenario {
            parallel {
                thread { actor(::foo) }
                thread { actor(::bar) }
            }
        }
    }
        .checkImpl(this::class.java)
        .checkLincheckOutput("suspend_trace_resumption_frame_skipping_reporting_test")
}
