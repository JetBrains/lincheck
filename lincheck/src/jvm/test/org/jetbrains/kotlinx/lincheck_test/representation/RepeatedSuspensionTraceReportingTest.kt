/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Checks repeated suspension of the same nested call, including an exception after resumption. */
class RepeatedSuspensionTraceReportingTest {
    private var continuation: Continuation<Unit>? = null
    private var counter = 0

    @Operation(cancellableOnSuspension = false)
    suspend fun operation() {
        nested()
        counter++
    }

    private suspend fun nested() {
        suspendCoroutine<Unit> { continuation = it }
        counter++
        suspendCoroutine<Unit> { continuation = it }
        error("resumed twice")
    }

    @Operation
    fun resume() {
        while (continuation == null) Thread.yield()
        val current = continuation!!
        continuation = null
        current.resume(Unit)
    }

    @Test
    fun test() {
        val failure = ModelCheckingOptions()
            .addCustomScenario {
                parallel {
                    thread { actor(::operation) }
                    thread {
                        actor(::resume)
                        actor(::resume)
                    }
                }
            }
            .iterations(0)
            .verifier(FailingVerifier::class.java)
            .checkImpl(this::class.java)
        // Rendering must not overwrite the suspension results in the collected trace.
        repeat(2) { failure.checkLincheckOutput("repeated_suspension") }
    }
}
