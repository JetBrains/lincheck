/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import java.lang.IllegalStateException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

@OptIn(InternalCoroutinesApi::class)
class CoroutinesMultipleSuspensionsTest : AbstractLincheckTest() {
    private val locked = atomic(false)
    private val waiters = ConcurrentLinkedQueue<CancellableContinuation<Unit>>()

    @Operation(allowExtraSuspension = true)
    suspend fun lock() {
        while (true) {
            if (locked.compareAndSet(false, true)) return
            suspendCancellableCoroutine<Unit> { cont ->
                waiters.add(cont)
                if (!locked.value) {
                    if (waiters.remove(cont)) cont.resume(Unit)
                }
            }
        }
    }

    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    fun unlock() {
        if (!locked.compareAndSet(true, false)) error("mutex was not locked")
        while (true) {
            val w = waiters.poll() ?: break
            val token = w.tryResume(Unit, null) ?: continue
            w.completeResume(token)
            return
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        sequentialSpecification(MutexSequential::class.java)
    }
}

class MutexSequential {
    private val m = Mutex()

    suspend fun lock() = m.lock()
    fun unlock() = m.unlock()
}