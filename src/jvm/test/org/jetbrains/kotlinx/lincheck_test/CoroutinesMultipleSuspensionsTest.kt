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
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

/**
 * Checks the data structure which actors may suspend multiple times.
 * Passes only if the multiple suspension points are supported properly.
 */
@OptIn(InternalCoroutinesApi::class)
class CoroutinesMultipleSuspensionsTest : AbstractLincheckTest() {
    private val locked = atomic(false)
    private val waiters = ConcurrentLinkedQueue<CancellableContinuation<Unit>>()

    @Operation
    suspend fun lock() {
        while (true) {
            if (locked.compareAndSet(false, true)) return
            suspendCancellableCoroutine { cont ->
                waiters.add(cont)
                if (!locked.value) {
                    if (waiters.remove(cont)) cont.resume(Unit)
                }
            }
        }
    }

    @Operation
    fun unlock() {
        if (!locked.compareAndSet(true, false)) error("mutex was not locked")
        while (true) {
            val w = waiters.poll() ?: break
            val token = w.tryResume(Unit, null) ?: continue
            w.completeResume(token)
            return
        }
    }
}