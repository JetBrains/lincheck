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
import org.jetbrains.kotlinx.lincheck.*
import kotlin.native.ThreadLocal
import kotlin.native.concurrent.*
import kotlin.system.*

@ThreadLocal
val currentThreadId = Any()

internal actual class TestThread actual constructor(val iThread: Int, val runnerHash: Int, val r: Runnable) {
    val worker: Worker = Worker.start()

    actual fun start() {
        //printErr("start() $iThread called")
        worker.execute(TransferMode.UNSAFE, { r }, { r -> r.run() })
    }

    actual fun stop() {
        //printErr("stop() $iThread called")
        worker.requestTermination(false)
        //printErr("stop() $iThread finished")
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
            //platform.posix.sleep((nanos / 1_000_000_000).toUInt())
        }
    }
}

internal actual fun currentTimeMillis() = getTimeMillis()