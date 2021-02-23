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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This exception is used to emulate system crash.
 * Must be ignored by user code, namely 'catch (e: Throwable)' constructions should pass this exception.
 */
abstract class CrashError(enableStackTrace: Boolean) : Throwable(null, null, false, enableStackTrace) {
    abstract var actorIndex: Int
    abstract val crashStackTrace: Array<StackTraceElement>
}

class CrashErrorImpl(override var actorIndex: Int = -1) : CrashError(true) {
    override val crashStackTrace: Array<StackTraceElement> = stackTrace
}

/** Proxy provided to minimize [fillInStackTrace] calls as it influence performance a lot. */
class CrashErrorProxy(
    private val className: String?,
    private val fileName: String?,
    private val methodName: String?,
    private val lineNumber: Int,
    override var actorIndex: Int = -1
) : CrashError(false) {
    override val crashStackTrace get() = arrayOf(StackTraceElement(className, methodName, fileName, lineNumber))
}

private data class SystemContext(val barrier: BusyWaitingBarrier?, val threads: Int)

object Crash {
    private val systemCrashOccurred = AtomicBoolean(false)

    @JvmStatic
    fun isCrashed() = systemCrashOccurred.get()

    @JvmStatic
    fun resetAllCrashed() {
        systemCrashOccurred.compareAndSet(true, false)
    }

    /**
     * Crash simulation.
     * @throws CrashError
     */
    private fun crash(threadId: Int, className: String?, fileName: String?, methodName: String?, lineNumber: Int) {
        val crash = createCrash(className, fileName, methodName, lineNumber)
        RecoverableStateContainer.registerCrash(threadId, crash)
        throw crash
    }

    private fun createCrash(className: String?, fileName: String?, methodName: String?, lineNumber: Int): CrashError {
        return if (useProxyCrash) {
            CrashErrorProxy(className, fileName, methodName, lineNumber)
        } else {
            CrashErrorImpl()
        }
    }

    private val context = atomic(SystemContext(null, 0))
    internal val threads get() = context.value.threads
    private var awaitSystemCrashBeforeThrow = true
    var useProxyCrash = true

    /**
     * Random crash simulation. Produces a single thread crash or a system crash.
     * On a system crash a thread waits for other threads to reach this method call.
     */
    @JvmStatic
    fun possiblyCrash(className: String?, fileName: String?, methodName: String?, lineNumber: Int) {
        if (context.value.barrier !== null) {
            val threadId = RecoverableStateContainer.threadId()
            if (awaitSystemCrashBeforeThrow) awaitSystemCrash()
            crash(threadId, className, fileName, methodName, lineNumber)
        }
        if (Probability.shouldCrash()) {
            val threadId = RecoverableStateContainer.threadId()
            if (awaitSystemCrashBeforeThrow && Probability.shouldSystemCrash()) awaitSystemCrash()
            crash(threadId, className, fileName, methodName, lineNumber)
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
        resetAllCrashed()
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
