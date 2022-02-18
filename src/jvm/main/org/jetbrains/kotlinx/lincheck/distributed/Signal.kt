/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.distributed

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Simple wait() / notify() mechanism for coroutines.
 * It is not thread-safe and can be used only for coroutines running in one thread.
 */
open class Signal {
    private var continuation: CancellableContinuation<Unit>? = null

    /**
     * Suspends the coroutine until the signal is received.
     */
    suspend fun await() {
        suspendCancellableCoroutine<Unit> { cont ->
            continuation = cont
        }
    }

    /**
     * Signals to the awaiting coroutine.
     */
    fun signal() {
        if (continuation?.isActive == true) {
            val cont = continuation
            continuation = null
            cont?.resume(Unit)
        }
    }
}
