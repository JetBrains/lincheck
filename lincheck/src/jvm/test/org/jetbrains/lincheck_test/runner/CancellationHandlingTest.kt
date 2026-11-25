/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.runner

import org.jetbrains.lincheck.LincheckSettings
import kotlinx.coroutines.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck_test.AbstractLincheckTest
import java.util.concurrent.atomic.*

class CancellationHandlingTest : AbstractLincheckTest() {
    @Volatile
    private var suspendedContOrCancelled = AtomicReference<Any?>(null)

    @InternalCoroutinesApi
    @Operation(runOnce = true)
    suspend fun suspendIfNotClosed() = suspendCancellableCoroutine<Unit> { cont ->
        val cancelled = !suspendedContOrCancelled.compareAndSet(null, cont)
        if (cancelled) cont.cancel()
    }

    @Operation(runOnce = true)
    fun cancelSuspended() {
        val cont = suspendedContOrCancelled.getAndSet(CANCELLED)
        if (cont === null) return
        @Suppress("UNCHECKED_CAST")
        (cont as CancellableContinuation<Unit>).cancel()
    }

    override fun LincheckSettings.customize() {
        // We ignore this test with EventStructure for now, as it uses coroutines, which are not currently supported
        ignoreExperimentalModelChecking()
        scenarioGeneration {
            operationsBefore = 0
            operationsAfter = 0
            randomScenarios = 1
        }
    }
}

private val CANCELLED = Any()
