/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import kotlinx.coroutines.*
import org.jetbrains.lincheck.datastructures.Options
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.StressOptions

class IncorrectOnCancellationTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    @Volatile
    var canEnterForbiddenSection = false

    @InternalCoroutinesApi
    @Operation(cancellableOnSuspension = true)
    suspend fun cancelledOp(): Int {
        if (canEnterForbiddenSection)
            return 42
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                canEnterForbiddenSection = true
                canEnterForbiddenSection = false
            }
        }
        return 0
    }

    override fun <O : Options<O, *>> O.customize() {
        if (this is StressOptions) {
            invocationsPerIteration(10_000)
        }
    }
}