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

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.test.util.runModelCheckingTestAndCheckOutput
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

class CoroutineCancellationTraceReportingTest : VerifierState() {
    @Volatile
    var correct = true

    @InternalCoroutinesApi
    @Operation(cancellableOnSuspension = true)
    suspend fun cancelledOp() {
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                correct = false
            }
        }
    }

    @Operation
    fun isAbsurd(): Boolean = correct && !correct

    override fun extractState(): Any = correct

    @Test
    fun test() = runModelCheckingTestAndCheckOutput( "coroutine_cancellation.txt") {
        actorsPerThread(1)
        actorsBefore(0)
        actorsAfter(0)
    }

}