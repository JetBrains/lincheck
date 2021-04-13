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

import kotlin.native.concurrent.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlinx.coroutines.*
import kotlin.native.ThreadLocal
import kotlin.native.concurrent.*
import kotlin.system.*

@ThreadLocal
val currentThreadId = Any()

val results = mutableSetOf<Any?>()

internal actual class TestThread actual constructor(val iThread: Int, val runnerHash: Int, val r: Runnable) {
    val worker: Worker = Worker.start(true, "Worker $iThread $runnerHash")
    var runnableFuture: Future<Runnable>? = null

    actual fun execute() {
        //printErr("start() $iThread called")
        runnableFuture = worker.execute(TransferMode.UNSAFE, { r }, { r ->
            r.run()
            r
        })
    }

    actual fun terminate() {
        //printErr("stop() $iThread called")
        //val res = runnableFuture?.result
        //println("terminate $iThread start")
        val result = runnableFuture!!.result
        worker.execute(TransferMode.UNSAFE, { }, { sleep(1000000000) })
        //println("terminate $iThread end")
        //worker.requestTermination(true).result
        //return res
        //printErr("stop() $iThread finished")
    }

    actual companion object {
        actual fun currentThread(): Any? = currentThreadId
    }
}

internal actual class LockSupport {
    actual companion object {
        actual fun park() {
            //usleep(1000.toUInt()) // 1ms
        }

        actual fun unpark(thread: Any?) {
        }

        actual fun parkNanos(nanos: Long) {
            //usleep(1000.toUInt()) // 1ms
            //platform.posix.sleep((nanos / 1_000_000_000).toUInt())
        }
    }
}

internal actual fun currentTimeMillis() = getTimeMillis()