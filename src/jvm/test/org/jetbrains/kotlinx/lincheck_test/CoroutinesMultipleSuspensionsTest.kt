/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.lincheck.datastructures.Operation
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume
import org.junit.Ignore

/**
 * Checks the data structure which actors may suspend multiple times.
 * Passes only if the multiple suspension points are supported properly.
 */
@Ignore // TODO: also requires support of multiple suspension points in linearizability checker
@OptIn(InternalCoroutinesApi::class)
class CoroutinesMultipleSuspensionsTest : AbstractLincheckTest() {
    private var counter = 0
    private val locked = atomic(false)
    private val waiters = ConcurrentLinkedQueue<CancellableContinuation<Unit>>()

    private suspend fun lock() {
        while (true) {
            if (locked.compareAndSet(false, true)) return
            suspendCancellableCoroutine { cont ->
                waiters.add(cont)
                if (!locked.value && waiters.remove(cont)) {
                    cont.resume(Unit)
                }
            }
        }
    }

    private suspend fun unlock() {
        if (!locked.compareAndSet(true, false)) error("mutex was not locked")
        while (true) {
            val w = waiters.poll() ?: break
            val token = w.tryResume(Unit, null) ?: continue
            w.completeResume(token)
            return
        }
    }

    private suspend fun <R> withLock(block: suspend () -> R): R {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun operation(): Int {
        return withLock { counter++ }
    }
}