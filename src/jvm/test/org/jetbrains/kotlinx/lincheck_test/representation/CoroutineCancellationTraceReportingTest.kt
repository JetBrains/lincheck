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

import kotlinx.coroutines.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.*

// TODO investigate difference for trace debugger (Evgeniy Moiseenko)
class CoroutineCancellationTraceReportingTest {
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

    @Test
    fun test() = ModelCheckingOptions().apply {
        actorsPerThread(1)
        actorsBefore(0)
        actorsAfter(0)
    }
        .checkImpl(this::class.java)
        .checkLincheckOutput("coroutine_cancellation")

}