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

private data class SystemContext(val barrier: BusyWaitingBarrier?, val threads: Int)

object Crash {
    val systemCrashOccurred = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Crash simulation.
     * @throws CrashError
     */
    private fun crash(threadId: Int) {
        throw CRASH.also { RecoverableStateContainer.registerCrash(threadId, it) }
    }

    private val CRASH = CrashError()

    private val context = atomic(SystemContext(null, 0))
    val threads get() = context.value.threads
    var awaitSystemCrashBeforeThrow = true

    /**
     * Random crash simulation. Produces a single thread crash or a system crash.
     * On a system crash a thread waits for other threads to reach this method call.
     */
    @JvmStatic
    fun possiblyCrash() {
        if (context.value.barrier !== null) {
            val threadId = RecoverableStateContainer.threadId()
            if (awaitSystemCrashBeforeThrow) awaitSystemCrash()
            crash(threadId)
        }
        if (Probability.shouldCrash()) {
            val threadId = RecoverableStateContainer.threadId()
            if (awaitSystemCrashBeforeThrow && Probability.shouldSystemCrash()) awaitSystemCrash()
            crash(threadId)
        }
    }

    /**
     * Await for all active threads to access this point and crash the cache.
     */
    @JvmStatic
    fun awaitSystemCrash() {
        var newBarrier: BusyWaitingBarrier? = null
        while (true) {
            val c = context.value
            if (c.barrier !== null) break
            if (newBarrier === null) newBarrier = BusyWaitingBarrier()
            if (context.compareAndSet(c, c.copy(barrier = newBarrier))) break
        }
        context.value.barrier!!.await { first ->
            if (!first) return@await
            systemCrashOccurred.compareAndSet(false, true)
            NVMCache.systemCrash()
            while (true) {
                val currentContext = context.value
                checkNotNull(currentContext.barrier)
                if (context.compareAndSet(currentContext, currentContext.copy(barrier = null))) break
            }
        }
    }

    /** Should be called when thread finished. */
    fun exit(threadId: Int) {
        while (true) {
            val currentContext = context.value
            if (context.compareAndSet(currentContext, currentContext.copy(threads = currentContext.threads - 1))) break
        }
    }

    /** Should be called when thread started. */
    fun register(threadId: Int) {
        while (true) {
            val currentContext = context.value
            if (currentContext.barrier !== null) continue
            if (context.compareAndSet(currentContext, currentContext.copy(threads = currentContext.threads + 1))) break
        }
    }

    fun reset(recoverModel: RecoverabilityModel) {
        awaitSystemCrashBeforeThrow = recoverModel.awaitSystemCrashBeforeThrow
        context.value = SystemContext(null, 0)
        systemCrashOccurred.set(false)
    }
}

class BusyWaitingBarrier {
    internal val free = atomic(false)
    internal val waitingCount = atomic(0)

    internal inline fun await(action: (Boolean) -> Unit) {
        waitingCount.incrementAndGet()
        // wait for all to access the barrier
        while (waitingCount.value < Crash.threads && !free.value);
        val firstExit = free.compareAndSet(expect = false, update = true)
        action(firstExit)
        waitingCount.decrementAndGet()
        // wait for action completed in all threads
        while (waitingCount.value > 0 && free.value);
    }
}
