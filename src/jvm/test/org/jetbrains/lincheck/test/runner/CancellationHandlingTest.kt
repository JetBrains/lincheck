/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck.test.runner

import kotlinx.coroutines.*
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.annotations.*
import org.jetbrains.lincheck.test.AbstractLincheckTest
import java.util.concurrent.atomic.*

class CancellationHandlingTest : AbstractLincheckTest() {
    @Volatile
    private var suspendedContOrCancelled = AtomicReference<Any?>(null)

    @InternalCoroutinesApi
    @Operation(runOnce = true, handleExceptionsAsResult = [CancellationException::class])
    suspend fun suspendIfNotClosed() = suspendCancellableCoroutine<Unit> { cont ->
        val cancelled = !suspendedContOrCancelled.compareAndSet(null, cont)
        if (cancelled) cont.cancel()
    }

    @Operation(runOnce = true)
    fun cancelSuspended() {
        val cont = suspendedContOrCancelled.getAndSet(CANCELLED)
        if (cont === null) return
        (cont as CancellableContinuation<Unit>).cancel()
    }

    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(0)
        actorsAfter(0)
        iterations(1)
    }
}

private val CANCELLED = Any()
