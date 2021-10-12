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
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This exception is used to emulate a crash.
 * Must be ignored by user code, namely 'catch (e: Throwable)' constructions should pass this exception.
 */
abstract class CrashError internal constructor(enableStackTrace: Boolean) : Throwable(null, null, false, enableStackTrace) {
    var actorIndex: Int = -1
    abstract val crashStackTrace: Array<StackTraceElement>
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CrashError) return false

        if (actorIndex != other.actorIndex) return false
        if (!crashStackTrace.contentEquals(other.crashStackTrace)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = actorIndex
        result = 31 * result + crashStackTrace.contentHashCode()
        return result
    }
}

/**
 * A usual exception with a stacktrace collected.
 * Stack trace collection is a long operation, so [CrashErrorProxy] is used whenever it is possible.
 * This error is used to present the final results.
 */
private class CrashErrorImpl : CrashError(true) {
    override val crashStackTrace: Array<StackTraceElement> get() = stackTrace
}

/**
 * Proxy provided to minimize [fillInStackTrace] calls as it influence performance a lot.
 * Contains only one stack frame element.
 */
private class CrashErrorProxy(private val ste: StackTraceElement?) : CrashError(false) {
    override val crashStackTrace get() = if (ste === null) emptyArray() else arrayOf(ste)
}

/**
 * Current state describing active threads.
 * The class is immutable to perform an atomic change of it's values.
 */
private data class SystemContext(
    /** The number of threads waiting for a system crash. */
    val waitingThreads: Int,
    /** The number of active threads. */
    val threads: Int,
    /** A lock for the threads waiting in a barrier. */
    val free: AtomicBoolean = AtomicBoolean(true)
)

/** Crash related utils. */
internal class Crash(private val state: NVMState, recoverModel: RecoverabilityModel) {
    /** A flag whether a system crash occurred and not handled be some recover method yet. */
    private val systemCrashOccurred = AtomicBoolean(false)

    /** The current active threads context. */
    private val context = atomic(SystemContext(0, 0))

    /** A flag whether threads must wait for each other before throwing an exception. */
    private val awaitSystemCrashBeforeThrow = recoverModel.awaitSystemCrashBeforeThrow
    internal val threads get() = context.value.threads

    @Volatile
    private var execution: TestThreadExecution? = null

    /** Thread ids of currently active threads. */
    private val activeThreads = Collections.synchronizedList(mutableListOf<Int>())

    /**
     * A flag whether to use a proxy instead of usual exception.
     */
    private val useProxyCrash = LinChecker.useProxyCrash.get() ?: true

    /** An action that must be performed before a barrier opening. */
    @Volatile
    internal var barrierCallback: () -> Unit = { defaultAwaitSystemCrash() }

    internal fun isCrashed() = systemCrashOccurred.get()

    /**
     * Set the last system crash handled.
     * Invoked after some recover method completion.
     */
    internal fun resetAllCrashed() {
        systemCrashOccurred.compareAndSet(true, false)
    }

    /**
     * Crash simulation.
     * @throws CrashError
     */
    internal fun crash(threadId: Int, ste: StackTraceElement?, systemCrash: Boolean) {
        if (awaitSystemCrashBeforeThrow && systemCrash) awaitSystemCrash(null)
        val crash = createCrash(ste)
        state.registerCrash(threadId, crash)
        throw crash
    }

    private fun createCrash(ste: StackTraceElement?) = if (useProxyCrash) CrashErrorProxy(ste) else CrashErrorImpl()

    /**
     * Random crash simulation. Produces a single thread crash or a system crash.
     * On a system crash a thread waits for other threads to reach this method call.
     */
    internal fun possiblyCrash(className: String?, fileName: String?, methodName: String?, lineNumber: Int) {
        if (isWaitingSystemCrash() || state.probability.shouldCrash()) {
            val ste = StackTraceElement(className, methodName, fileName, lineNumber)
            val systemCrash = isWaitingSystemCrash() || state.probability.shouldSystemCrash()
            crash(state.currentThreadId(), ste, systemCrash)
        }
    }

    /**
     * Await for all active threads to access this point and crash the cache.
     */
    internal fun awaitSystemCrash(execution: TestThreadExecution?) {
        this.execution = execution
        barrierCallback()
    }

    /** This waiting method is used in the stress strategy. */
    private fun defaultAwaitSystemCrash() {
        var free: AtomicBoolean
        while (true) {
            val c = context.value
            val newWaiting = c.waitingThreads + 1
            free = if (c.waitingThreads == 0) AtomicBoolean(false) else c.free
            if (changeState(c, newWaiting == c.threads, c.threads, newWaiting, free)) break
        }
        while (!free.get());
    }

    /**
     * An action that must be invoked before the barrier opening.
     */
    internal fun onSystemCrash() {
        systemCrashOccurred.compareAndSet(false, true)
        state.cache.systemCrash()
        state.setCrashedActors()
        val exec = execution ?: return
        activeThreads.forEach {
            exec.allThreadExecutions[it - 1].incClock()
        }
    }

    /** Should be called when thread finished. */
    fun exitThread() {
        activeThreads.remove(state.currentThreadId())
        while (true) {
            val c = context.value
            val newThreads = c.threads - 1
            val isLast = c.waitingThreads == newThreads && c.waitingThreads > 0
            if (changeState(c, isLast, newThreads, c.waitingThreads, c.free)) break
        }
    }

    /** Should be called when thread started. */
    fun registerThread() {
        while (true) {
            val currentContext = context.value
            if (currentContext.waitingThreads != 0) continue
            if (context.compareAndSet(currentContext, currentContext.copy(threads = currentContext.threads + 1))) break
        }
        activeThreads.add(state.currentThreadId())
    }

    fun reset() {
        context.value = SystemContext(0, 0)
        resetAllCrashed()
        execution = null
        activeThreads.clear()
    }

    private fun isWaitingSystemCrash() = context.value.waitingThreads > 0

    /** Atomically update the [context] state. */
    private fun changeState(
        c: SystemContext,
        isLast: Boolean,
        newThreads: Int,
        newWaiting: Int,
        newFree: AtomicBoolean
    ): Boolean {
        var newW = newWaiting
        if (isLast) {
            onSystemCrash()
            newW = 0 // reset barrier
        }
        return context.compareAndSet(c, SystemContext(newW, newThreads, newFree)).also { success ->
            if (success && isLast)
                check(newFree.compareAndSet(false, true)) // open barrier
        }
    }
}
