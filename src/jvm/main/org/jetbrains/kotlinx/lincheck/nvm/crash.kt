/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.nvm

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.runner.RecoverableStateContainer

/**
 * This exception is used to emulate system crash.
 * Must be ignored by user code, namely 'catch (e: Throwable)' constructions should pass this exception.
 */
class CrashError(var actorIndex: Int = -1) : Error()

object Crash {
    /**
     * Crash simulation.
     * @throws CrashError
     */
    private fun crash(threadId: Int, systemCrash: Boolean) {
        throw CRASH.also { RecoverableStateContainer.registerCrash(threadId, it) }
    }

    private val CRASH = CrashError()

    private val threadsCount = atomic(0)
    internal val barrier = atomic<BusyWaitingBarrier?>(null)
    val threads get() = threadsCount.value

    /**
     * Random crash simulation. Produces a single thread crash or a system crash.
     * On a system crash a thread waits for other threads to reach this method call.
     */
    @JvmStatic
    fun possiblyCrash() {
        if (barrier.value !== null) {
            val threadId = RecoverableStateContainer.threadId()
            crash(threadId, systemCrash = true)
        }
        if (Probability.shouldCrash()) {
            val threadId = RecoverableStateContainer.threadId()
            if (Probability.shouldSystemCrash()) {
                crash(threadId, systemCrash = true)
            } else {
                crash(threadId, systemCrash = false)
            }
        }
    }

    @JvmStatic
    fun awaitSystemCrash() {
        var b = barrier.value
        if (b == null) {
            barrier.compareAndSet(null, BusyWaitingBarrier())
            b = barrier.value!!
        }
        b.await()
    }

    /** Should be called when thread finished. */
    fun exit(threadId: Int) {
        threadsCount.decrementAndGet()
    }

    /** Should be called when thread started. */
    fun register(threadId: Int) {
        threadsCount.incrementAndGet()
    }

    fun reset() {
        threadsCount.value = 0
        barrier.value = null
    }
}

class BusyWaitingBarrier {
    private val free = atomic(false)
    private val waitingCount = atomic(0)

    fun await() {
        waitingCount.incrementAndGet()
        // wait for all to access the barrier
        while (waitingCount.value < Crash.threads && !free.value) {
        }
        if (free.compareAndSet(expect = false, update = true)) {
            NVMCache.systemCrash()
        }
        Crash.barrier.compareAndSet(this, null)
        waitingCount.decrementAndGet()
        // wait for cache reset
        while (waitingCount.value > 0) {
        }
    }
}
