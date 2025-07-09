/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy

import sun.nio.ch.lincheck.TestThread
import sun.nio.ch.lincheck.ThreadDescriptor
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.ensureNull

/**
 * Enumeration representing the various states of a thread.
 * Loosely corresponds to [Thread.State] with a few changes.
 *
 * - [INITIALIZED] --- the thread was created, registered in Lincheck strategy, and started,
 *     but has not yet reported itself running to Lincheck
 *     (i.e., not yet called [Runner.onThreadStart])
 *
 * - [ENABLED] --- the thread is running under Lincheck analysis.
 *
 * - [BLOCKED] --- the thread was blocked (e.g., due to locks, parking, etc.).
 *
 * - [ABORTED] --- the thread aborted by the Lincheck.
 *      Note that the thread is still running while in this state until it actually finishes,
 *      but the Lincheck no longer tries to analyze the code in this thread.
 *
 * - [FINISHED] --- the thread finished running and reported to Lincheck about it.
 *
 * Here is a state diagram of the thread life cycle in Lincheck:
 *
 *     +-------------+      +----------+      +---------+
 *     | INITIALIZED | ---> | ENABLED |  <--> | BLOCKED |
 *     +-------------+      +---------+       +---------+
 *                            |   |
 *                            |   |      +---------+
 *                            |   +---> | ABORTED | ---+
 *                            |         +---------+    |
 *                            |                        |
 *                            |     +----------+       |
 *                            +---> | FINISHED | <-----+
 *                                  +----------+
 *
 */
enum class ThreadState {
    INITIALIZED,
    ENABLED,
    BLOCKED,
    ABORTED,
    FINISHED,
}

/**
 * Sealed hierarchy of classes representing possible reasons for thread blocking.
 */
sealed class BlockingReason {
    data object Locked : BlockingReason()
    data object LiveLocked : BlockingReason()
    data object Waiting : BlockingReason()
    data object Suspended : BlockingReason()
    data object Parked : BlockingReason()
    data class  ThreadJoin(val joinedThreadId: ThreadId) : BlockingReason()
}

fun BlockingReason.isInterruptible(): Boolean =
    this is BlockingReason.Parked       ||
    this is BlockingReason.Waiting      ||
    this is BlockingReason.ThreadJoin

fun BlockingReason.throwsInterruptedException(): Boolean =
    this is BlockingReason.Waiting      ||
    this is BlockingReason.ThreadJoin

/**
 * [ThreadScheduler] is responsible for controlling the lifecycle of threads
 * withing the Lincheck testing strategies.
 *
 * Note that because we do not have direct access to the underlying thread scheduler of the JVM,
 * the [ThreadScheduler] relies on cooperation from the strategies to control scheduling of the threads.
 * Typically, to achieve this, the analysis strategies instrument the code under test
 * to inject calls to specific strategy methods, taking the opportunity to intercept
 * certain thread events (e.g., thread start or finish) or to artificially block the thread execution.
 *
 */
open class ThreadScheduler {

    private val threads_ = mutableThreadMapOf<ThreadData>()
    protected val threads: ThreadMap<ThreadData> get() = threads_

    /**
     * Number of threads currently managed by the scheduler.
     */
    val nThreads: Int get() =
        threads.size

    protected open class ThreadData(
        val id: ThreadId,
        val descriptor: ThreadDescriptor,
        val scheduler: ThreadScheduler,
    ) {
        @Volatile var state: ThreadState = ThreadState.INITIALIZED

        @Volatile var blockingReason: BlockingReason? = null

        val spinner: Spinner = Spinner { scheduler.threads.size }
    }

    protected open fun createThreadData(id: ThreadId, descriptor: ThreadDescriptor): ThreadData {
        return ThreadData(id, descriptor, this)
    }

    /**
     * Retrieves all threads registered in the scheduler.
     *
     * @return a map from thread ids to thread instances that are currently registered.
     */
    fun getRegisteredThreads() : ThreadMap<Thread> = mutableThreadMapOf<Thread>().apply {
        for ((threadId, threadData) in threads) {
            val thread = threadData.descriptor.thread
            if (thread != null) {
                put(threadId, thread)
            }
        }
    }

    /**
     * Retrieves the identifier of the specified thread.
     * Thread id is within the [0 ... nThreads] range.
     *
     * @param thread The thread for which the id is to be retrieved.
     * @return The id of the thread, or -1 if the thread is not found.
     */
    fun getThreadId(thread: Thread): ThreadId {
        val descriptor = ThreadDescriptor.getThreadDescriptor(thread)
        return (descriptor?.eventTrackerData as? ThreadData)?.id ?: -1
    }

    fun getCurrentThreadId(): ThreadId {
        val descriptor = ThreadDescriptor.getCurrentThreadDescriptor()
        return (descriptor?.eventTrackerData as? ThreadData)?.id ?: -1
    }

    /**
     * Retrieves the thread associated with the given thread id.
     *
     * @param threadId The identifier of the thread to be retrieved.
     * @return The thread associated with the provided identifier or null if the thread is not found.
     */
    fun getThread(threadId: ThreadId): Thread? =
        threads[threadId]?.descriptor?.thread

    /**
     * Retrieves the current state of the thread for the specified thread id.
     *
     * @param threadId The identifier of the thread whose state is to be retrieved.
     * @return The current state of the thread, or null if the thread is not found.
     */
    fun getThreadState(threadId: ThreadId): ThreadState? =
        threads[threadId]?.state

    /**
     * Retrieves the blocking reason for the specified thread id.
     *
     * @param threadId The identifier of the thread whose blocking reason is to be retrieved.
     * @return The blocking reason of the thread, or null if the thread is not found or not blocked.
     */
    fun getBlockingReason(threadId: ThreadId): BlockingReason? =
        threads[threadId]?.blockingReason

    /**
     * Checks if the thread is currently enabled.
     *
     * @param threadId The identifier of the thread to check.
     * @return `true` if the thread is enabled, `false` otherwise.
     */
    fun isEnabled(threadId: ThreadId) =
        getThreadState(threadId) == ThreadState.ENABLED

    /**
     * Checks if the thread is currently schedulable.
     * The thread is schedulable if it is in INITIALIZED or ENABLED state.
     *
     * @param threadId The identifier of the thread to check.
     * @return `true` if the thread is schedulable, `false` otherwise.
     */
    fun isSchedulable(threadId: ThreadId): Boolean {
        val state = threads[threadId]?.state ?: return false
        return (state == ThreadState.INITIALIZED) || (state == ThreadState.ENABLED)
    }

    /**
     * Checks if the thread is currently blocked.
     *
     * @param threadId The identifier of the thread to check.
     * @return `true` if the thread is blocked, `false` otherwise.
     */
    fun isBlocked(threadId: ThreadId) =
        getThreadState(threadId) == ThreadState.BLOCKED

    /**
     * Checks if the thread is currently live-locked.
     *
     * @param threadId The identifier of the thread to check.
     * @return `true` if the thread is live-locked, `false` otherwise.
     */
    fun isLiveLocked(threadId: ThreadId) =
        getBlockingReason(threadId) is BlockingReason.LiveLocked

    /**
     * Checks if the thread is currently parked.
     *
     * @param threadId The identifier of the thread to check.
     * @return `true` if the thread is parked, `false` otherwise.
     */
    fun isParked(threadId: ThreadId) =
        getBlockingReason(threadId) is BlockingReason.Parked

    /**
     * Checks if the thread was aborted.
     *
     * @param threadId The identifier of the thread to check.
     * @return `true` if the thread is aborted, `false` otherwise.
     */
    fun isAborted(threadId: ThreadId) =
        getThreadState(threadId) == ThreadState.ABORTED

    /**
     * Checks if the thread has finished its execution.
     *
     * @param threadId The identifier of the thread to check.
     * @return `true` if the thread is finished, `false` otherwise.
     */
    fun isFinished(threadId: ThreadId) =
        getThreadState(threadId) == ThreadState.FINISHED

    /**
     * Checks if all threads managed by the scheduler have finished execution.
     *
     * @return `true` if all threads are finished, otherwise `false`.
     */
    fun areAllThreadsFinished() =
        threads.values.all { it.state == ThreadState.FINISHED }

    /**
     * Checks if all threads managed by the scheduler have finished or aborted.
     * @return `true` if all threads are finished or aborted, otherwise `false`.
     */
    fun areAllThreadsFinishedOrAborted() =
        threads.values.all {
            it.state == ThreadState.FINISHED ||
            it.state == ThreadState.ABORTED
        }

    /**
     * Registers a new thread in the scheduler and assigns it a unique identifier.
     * The new thread initially is in [ThreadState.INITIALIZED] state.
     *
     * Generally should be called before the thread is forked (i.e., [Thread.start] is called).
     *
     * @param thread The thread to be registered.
     * @return The unique identifier assigned to the registered thread.
     */
    fun registerThread(thread: Thread, descriptor: ThreadDescriptor): ThreadId {
        val threadId = threads.size
        val threadData = createThreadData(threadId, descriptor)
        if (thread is TestThread) {
            check(threadId == thread.threadId)
        }
        threads_.put(threadId, threadData).ensureNull()
        descriptor.eventTrackerData = threadData
        return threadId
    }

    /**
     * Notifies that a thread actually started its execution.
     * The thread transitions into [ThreadState.ENABLED] state.
     *
     * Not to be confused with [Thread.start] --- this method should be
     * called from the running thread itself to report that it has started.
     *
     * @param threadId The identifier of the started thread.
     * @throws IllegalStateException if the thread is not in the [ThreadState.INITIALIZED] state.
     */
    fun startThread(threadId: ThreadId) {
        threads[threadId]!!.apply {
            check(state == ThreadState.INITIALIZED)
            state = ThreadState.ENABLED
        }
    }

    /**
     * Blocks the thread.
     * The thread state becomes [ThreadState.BLOCKED].
     *
     * @param threadId The identifier of the thread to be blocked.
     * @param reason The reason why the thread is being blocked.
     */
    fun blockThread(threadId: ThreadId, reason: BlockingReason) {
        threads[threadId]!!.apply {
            blockingReason = reason
            state = ThreadState.BLOCKED
        }
    }

    /**
     * Unblocks the thread.
     * The thread state becomes [ThreadState.ENABLED].
     *
     * @param threadId The identifier of the thread to unblock.
     */
    fun unblockThread(threadId: ThreadId) {
        threads[threadId]!!.apply {
            state = ThreadState.ENABLED
            blockingReason = null
        }
    }

    /**
     * Aborts the thread.
     * The thread state becomes [ThreadState.ABORTED].
     *
     * @param threadId The identifier of the thread to abort.
     */
    fun abortThread(threadId: ThreadId) {
        threads[threadId]!!.apply {
            state = ThreadState.ABORTED
        }
    }

    /**
     * Aborts all threads controlled by the scheduler.
     *
     * @see abortThread
     */
    fun abortAllThreads() {
        for (thread in threads.values) {
            if (thread.state == ThreadState.FINISHED)
                continue
            thread.state = ThreadState.ABORTED
        }
    }

    /**
     * Aborts all threads controlled by the scheduler, except the current thread.
     *
     * @see abortThread
     */
    fun abortOtherThreads() {
        val currentThreadId = getCurrentThreadId()
        for (thread in threads.values) {
            if (thread.state == ThreadState.FINISHED || thread.id == currentThreadId)
                continue
            thread.state = ThreadState.ABORTED
        }
    }

    /**
     * Marks the specified thread as finished.
     * The thread states transitions to [ThreadState.FINISHED].
     *
     * Generally should be called just before the thread is actually going to finish its execution.
     *
     * @param threadId The identifier of the thread to be marked as finished.
     */
    fun finishThread(threadId: ThreadId) {
        threads[threadId]!!.apply {
            state = ThreadState.FINISHED
        }
    }

    /**
     * Awaits the completion of a thread up to a specified timeout.
     *
     * @param threadId The identifier of the thread to await.
     * @param timeoutNano The maximum time to wait in nanoseconds.
     * @return The elapsed time in nanoseconds if the thread finishes or aborts;
     *   -1 if the timeout is reached.
     */
    fun awaitThreadFinish(threadId: ThreadId, timeoutNano: Long): Long {
        val threadData = threads[threadId]!!
        // special handling of Lincheck test threads
        if (threadData.descriptor.thread is TestThread) {
            val elapsedTime = threadData.spinner.spinWaitTimedUntil(timeoutNano) {
                threadData.state == ThreadState.FINISHED ||
                // TODO: due to limitations of current implementation,
                //   Lincheck test threads sometime end up in ABORTED state,
                //   even though they are actually finished
                threadData.state == ThreadState.ABORTED
            }
            return elapsedTime
        }
        val startTime = System.nanoTime()
        val timeoutMs = timeoutNano / 1_000_000
        threadData.descriptor.thread?.join(timeoutMs, (timeoutNano % 1_000_000).toInt())
        val elapsedTime = System.nanoTime() - startTime
        return if (elapsedTime < timeoutNano) elapsedTime else -1
    }

    /**
     * Awaits the completion of all threads up to a specified timeout.
     *
     * @param timeoutNano The maximum time to wait in nanoseconds for all threads to finish.
     * @return The elapsed time in nanoseconds if all threads finish within the timeout;
     *   -1 if the timeout is reached before all threads finish.
     */
    fun awaitAllThreadsFinish(timeoutNano: Long): Long {
        var remainingTime = timeoutNano
        for (threadData in threads.values) {
            val elapsedTime = awaitThreadFinish(threadData.id, remainingTime)
            if (elapsedTime < 0) return -1
            remainingTime -= elapsedTime
        }
        return (timeoutNano - remainingTime)
    }

    /**
     * Resets the thread scheduler by removing all registered threads.
     */
    fun reset() {
        threads_.clear()
    }

}