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
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Executor for distributed algorithms.
 * Counts the pending tasks and, if no tasks left, launches next task.
 */
internal class DistributedExecutor(private val runner: DistributedRunner<*>) : Executor {
    private val executor = Executors.newSingleThreadExecutor()

    private val counter = atomic(0)

    fun close() {
        executor.shutdown()
    }

    fun shutdownNow() {
        executor.shutdownNow()
    }

    override fun execute(command: Runnable) {
        counter.incrementAndGet()
        try {
            executor.submit {
                command.run()
                val counter = counter.decrementAndGet()
                if (counter == 0) {
                    if (!runner.launchNextTask()) {
                        runner.signal()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            return
        }
    }
}