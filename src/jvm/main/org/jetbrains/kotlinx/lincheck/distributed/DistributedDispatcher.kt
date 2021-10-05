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

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

internal class DistributedDispatcher(private val runner: DistributedRunner<*, *>) : CoroutineDispatcher(),
    AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var taskCounter = 0

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        taskCounter++
        try {
            executor.submit {
                block.run()
                taskCounter--
                if (taskCounter == 0) {
                    if (!executor.isShutdown) runner.continuation?.resume(Unit)
                }
            }
        } catch (_: RejectedExecutionException) {
            return
        }
    }

    override fun close() {
        executor.shutdown()
    }
}