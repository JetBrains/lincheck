/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.test.runner

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*
import java.util.concurrent.atomic.*

@StressCTest(iterations = 1, actorsBefore = 0, actorsAfter = 0, threads = 2, requireStateEquivalenceImplCheck = false)
class CancellationHandlingTest {
    @Volatile
    private var suspendedContOrCancelled = AtomicReference<Any?>(null)

    @InternalCoroutinesApi
    @Operation(runOnce = true, handleExceptionsAsResult = [CancellationException::class])
    suspend fun suspendIfNotClosed() = suspendAtomicCancellableCoroutine<Unit> { cont ->
        val cancelled = !suspendedContOrCancelled.compareAndSet(null, cont)
        if (cancelled) cont.cancel()
    }

    @Operation(runOnce = true)
    fun cancelSuspended() {
        val cont = suspendedContOrCancelled.getAndSet(CANCELLED)
        if (cont === null) return
        (cont as CancellableContinuation<Unit>).cancel()
    }

    @Test
    fun test() = LinChecker.check(this::class.java)
}

private val CANCELLED = Any()
