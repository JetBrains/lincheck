/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.runner

import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.LincheckAnalysisAbortedError
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy
import org.jetbrains.lincheck.util.ensure
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.TestThread
import sun.nio.ch.lincheck.ThreadDescriptor
import java.io.*

/**
 * Interface defining a runner that executes invocations of a given code under analysis.
 */
interface Runner : Closeable {
    /**
     * Runs the next invocation.
     */
    fun runInvocation(): InvocationResult

    /**
     * Releases the resources used by the runner.
     */
    override fun close() {}
}

/**
 * Abstract class representing a thread-pool-based [Runner].
 *
 * This class provides support for:
 * - Initialization and management of a [Strategy] instance.
 * - Managing the event trackers of the threads in the pool.
 * - Collecting thread dumps of the threads in the pool.
 */
internal abstract class AbstractActiveThreadPoolRunner : Runner {

    /**
     * Represents the strategy used by the runner to analyze given code.
     */
    lateinit var strategy: Strategy
        private set

    /**
     * Thread pool executor utilized for managing concurrent tasks in the runner.
     */
    protected abstract val executor : ActiveThreadPoolExecutor

    /**
     * Initializes the strategy to be used in the runner.
     */
    fun initializeStrategy(strategy: Strategy) {
        this.strategy = strategy
    }

    /**
     * Sets up event trackers for threads managed by the runner.
     */
    protected fun setEventTracker() {
        val eventTracker = (strategy as? ManagedStrategy) ?: return
        executor.threads.forEachIndexed { i, thread ->
            val descriptor = Injections.registerThread(thread, eventTracker)
            eventTracker.registerThread(thread, descriptor)
                .ensure { threadId -> threadId == i }
        }
    }

    /**
     * Resets the event tracker for threads managed by the runner.
     */
    protected fun resetEventTracker() {
        if (!::strategy.isInitialized) return
        if (strategy !is ManagedStrategy) return
        for (thread in executor.threads) {
            Injections.unregisterThread(thread)
        }
    }

    /**
     * Determines if this runner manages the provided thread.
     */
    protected fun isCurrentRunnerThread(thread: Thread): Boolean =
        executor.threads.any { it === thread }

    /**
     * Collects the current thread dump from the threads managed by the runner.
     */
    protected fun collectThreadDump() = Thread.getAllStackTraces().filter { (t, _) ->
        t is TestThread && isCurrentRunnerThread(t)
    }

    /**
     * Releases the resources used by the runner.
     */
    override fun close() {
        super.close()
        executor.close()
    }
}

/**
 * Checks if the provided exception is considered an internal exception.
 * Internal exceptions are those used by the Lincheck itself
 * to control execution of the analyzed code.
 */
@Suppress("DEPRECATION") // ThreadDeath
internal fun isInternalException(exception: Throwable): Boolean =
    // is used to stop thread in `FixedActiveThreadsExecutor` via `thread.stop()`
    exception is ThreadDeath ||
    // is used to abort thread in `ManagedStrategy`
    exception is LincheckAnalysisAbortedError
