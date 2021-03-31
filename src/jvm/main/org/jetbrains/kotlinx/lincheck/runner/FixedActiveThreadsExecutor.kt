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
package org.jetbrains.kotlinx.lincheck.runner

import kotlinx.coroutines.CancellableContinuation
import java.lang.*

internal actual class TestThread actual constructor(val iThread: Int, val runnerHash: Int, r: Runnable) : Thread(r, "FixedActiveThreadsExecutor@$runnerHash-$iThread") {
    var cont: CancellableContinuation<*>? = null

    actual companion object {
        actual fun currentThread(): Any? = Thread.currentThread() // For storing identifier and then call unpark()
    }

    actual fun execute() = start()
    actual fun terminate() {
        stop()
    }
}

internal actual class LockSupport {
    actual companion object {
        actual fun park() = java.util.concurrent.locks.LockSupport.park()
        actual fun unpark(thread: Any?) = java.util.concurrent.locks.LockSupport.unpark(thread as Thread)
        actual fun parkNanos(nanos: Long) = java.util.concurrent.locks.LockSupport.parkNanos(nanos)
    }
}

internal actual fun currentTimeMillis() = System.currentTimeMillis()