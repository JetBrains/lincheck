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

private data class SystemContext(
    val barrier: BusyWaitingBarrier?,
    val threads: Int,
    val crash: Boolean
)

object Crash {
    /**
     * Crash simulation.
     * @throws CrashError
     */
    private fun crash(threadId: Int) {
        throw CRASH.also { RecoverableStateContainer.registerCrash(threadId, it) }
    }

    private val CRASH = CrashError()

    private val context = atomic(SystemContext(null, 0, false))
    val threads get() = context.value.threads

    /**
     * Random crash simulation. Produces a single thread crash or a system crash.
     * On a system crash a thread waits for other threads to reach this method call.
     */
    @JvmStatic
    fun possiblyCrash() {
        if (context.value.barrier !== null) {
            val threadId = RecoverableStateContainer.threadId()
            crash(threadId)
        }
        if (Probability.shouldCrash()) {
            val threadId = RecoverableStateContainer.threadId()
            if (Probability.shouldSystemCrash()) {
                crash(threadId)
            } else {
                crash(threadId)
            }
        }
    }

    @JvmStatic
    fun awaitSystemCrash(): BusyWaitingBarrier {
        var c: SystemContext
        val newBarrier = lazy { BusyWaitingBarrier() }
        do {
            c = context.value
            if (c.crash && c.barrier !== null) break
        } while (!context.compareAndSet(c, c.copy(barrier = newBarrier.value, crash = true)))
        val b = context.value.barrier!!
        b.await { first ->
            if (!first) return@await
            NVMCache.systemCrash()
            var currentContext: SystemContext
            do {
                currentContext = context.value
                check(currentContext.crash)
                checkNotNull(currentContext.barrier)
            } while (!context.compareAndSet(currentContext, currentContext.copy(barrier = null)))
        }
        return b
    }

    @JvmStatic
    fun awaitSystemRecover(b: BusyWaitingBarrier) {
        b.await { first ->
            if (!first) return@await
            var currentContext: SystemContext
            do {
                currentContext = context.value
                check(currentContext.crash)
            } while (!context.compareAndSet(currentContext, currentContext.copy(crash = false)))
        }
    }

    /** Should be called when thread finished. */
    fun exit(threadId: Int) {
        var c: SystemContext
        do {
            c = context.value
        } while (!context.compareAndSet(c, c.copy(threads = c.threads - 1)))
    }

    /** Should be called when thread started. */
    fun register(threadId: Int) {
        while (true) {
            val c = context.value
            if (c.crash) continue
            if (context.compareAndSet(c, c.copy(threads = c.threads + 1))) break
        }
    }

    fun reset() {
        context.value = SystemContext(null, 0, false)
    }
}

class BusyWaitingBarrier {
    internal val free = atomic(false)
    internal val waitingCount = atomic(0)

    internal inline fun await(action: (Boolean) -> Unit) {
        waitingCount.incrementAndGet()
        // wait for all to access the barrier
        while (waitingCount.value < Crash.threads && !free.value) {
        }
        val firstExit = free.compareAndSet(expect = false, update = true)
        action(firstExit)
        waitingCount.decrementAndGet()
        // wait for action completed in all threads
        while (waitingCount.value > 0 && free.value) {
        }
        free.compareAndSet(expect = true, update = false)
    }
}
