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

import kotlinx.coroutines.*
import kotlin.system.*

@ThreadLocal
val currentThreadId = Any()

internal actual class TestThread actual constructor(iThread: Int, runnerHash: Int, r: Runnable) {
    actual fun start() {
    }

    actual fun stop() {
    }

    actual companion object {
        actual fun currentThread(): Any? = currentThreadId
    }

}

internal actual class LockSupport {
    actual companion object {
        actual fun park() {
        }

        actual fun unpark(thread: Any?) {
        }

        actual fun parkNanos(nanos: Long) {
        }
    }
}

internal actual fun currentTimeMillis() = getTimeMillis()