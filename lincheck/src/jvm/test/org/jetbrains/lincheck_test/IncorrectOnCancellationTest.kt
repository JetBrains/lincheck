/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test

import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy
import kotlinx.coroutines.*
import org.jetbrains.lincheck.IncorrectResultsFailure
import org.jetbrains.lincheck.datastructures.Operation

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

    override fun LincheckSettings.customize() {
        // We ignore this test with EventStructure for now, as it uses coroutines, which are not currently supported
        ignoreExperimentalModelChecking()
        if (strategy is TestingStrategy.Stress) {
            scenarioGeneration { invocationsPerScenario = 10_000 }
        }
    }
}