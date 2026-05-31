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
import org.jetbrains.lincheck.util.Spinner
import java.util.Collections

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

    /**
     * Represents a handle to a thread managed by the thread scheduler
     *
     * Provides access to the thread's unique identifier, its descriptor, current state,
     * and other data related to the thread's lifecycle.
     *
     * Allows managing the thread lifecycle,
     * such as starting, blocking, unblocking, aborting, and completing the thread's execution.
     */
    interface ThreadHandle {
        /**
         * Unique identifier for the thread.
         */
        val id: ThreadId

        /**
         * Thread descriptor, see [ThreadDescriptor]
         */
        val descriptor: ThreadDescriptor

        /**
         * Current state of the thread, see [ThreadState].
         */
        val state: ThreadState

        /**
         * Reason for blocking the thread, see [BlockingReason].
         * Equals to null if the thread is not blocked currently.
         */
        val blockingReason: BlockingReason?

        /**
         * Scheduler that manages the thread.
         */
        val scheduler: ThreadScheduler

        /**
         * Notifies that a thread actually started its execution.
         * Transitions the thread from the [ThreadState.INITIALIZED] to [ThreadState.ENABLED] state.
         *
         * Not to be confused with [Thread.start] --- this method should be
         * called from the running thread itself to report that it has started.
         *
         * @throws IllegalStateException if the thread is not in the [ThreadState.INITIALIZED] state
         */
        fun startThread()

        /**
         * Blocks the thread associated with the current thread handle.
         * Transitions the thread state into [ThreadState.BLOCKED] and sets the blocking [reason].
         *
         * @param reason the reason of thread blocking.
         */
        fun blockThread(reason: BlockingReason)

        /**
         * Unblocks the thread associated with the current thread handle.
         * Transitions the thread state into [ThreadState.ENABLED] and clears the blocking reason.
         */
        fun unblockThread()

        /**
         * Aborts the thread associated with the current thread handle.
         * Transitions the thread state into [ThreadState.ABORTED].
         */
        fun abortThread()

        /**
         * Completes the execution of a thread associated with the current thread handle.
         * Transitions the thread state into the [ThreadState.FINISHED] state.
         */
        fun finishThread()
    }

    protected class ThreadHandleImpl(
        override val id: ThreadId,
        override val descriptor: ThreadDescriptor,
        override val scheduler: ThreadScheduler,
    ) : ThreadHandle {
        @Volatile
        override var state: ThreadState = ThreadState.INITIALIZED

        @Volatile
        override var blockingReason: BlockingReason? = null

        val spinner: Spinner = Spinner { scheduler.threads.size }

        override fun startThread() {
            check(state == ThreadState.INITIALIZED)
            state = ThreadState.ENABLED
        }

        override fun blockThread(reason: BlockingReason) {
            state = ThreadState.BLOCKED
            blockingReason = reason
        }

        override fun unblockThread() {
            state = ThreadState.ENABLED
            blockingReason = null
        }

        override fun abortThread() {
            state = ThreadState.ABORTED
        }

        override fun finishThread() {
            state = ThreadState.FINISHED
        }
    }

    /**
     * Collection of all threads managed by the scheduler.
     */
    val threads: List<ThreadHandle> get() = _threads
    protected val _threads: MutableList<ThreadHandleImpl> = Collections.synchronizedList(mutableListOf())

    protected fun createThreadHandle(id: ThreadId, descriptor: ThreadDescriptor): ThreadHandleImpl =
        ThreadHandleImpl(id, descriptor, this)

    /**
     * Retrieves the thread handle associated with the given thread descriptor.
     *
     * @param descriptor The descriptor of the thread for which the handle is to be retrieved.
     * @return The thread handle associated with the descriptor.
     * @throws IllegalStateException if the thread handle is not found for the given descriptor.
     */
    fun getThreadHandle(descriptor: ThreadDescriptor): ThreadHandle =
        (descriptor.eventTrackerData as? ThreadHandle)
            ?: error("Thread handle not found for descriptor: $descriptor")

    /**
     * Retrieves the handle of the current thread.
     *
     * @return The thread handle of the current thread.
     * @throws IllegalStateException if the current thread has no associated handle.
     */
    fun getCurrentThreadHandle(): ThreadHandle {
        val descriptor = ThreadDescriptor.getCurrentThreadDescriptor()
            ?: error("Current thread has no associated descriptor")
        return getThreadHandle(descriptor)
    }

    /**
     * Retrieves the thread handle associated with the given thread id.
     *
     * @param threadId The id of the thread for which the handle is to be retrieved.
     * @return The thread handle for the given thread id.
     * @throws IllegalStateException if no thread with the given id is registered in this scheduler.
     */
    fun getThreadHandle(threadId: ThreadId): ThreadHandle =
        _threads.getOrNull(threadId)
            ?: error("Thread handle not found for thread id: $threadId")

    /**
     * Retrieves the identifier of the specified thread.
     * Thread id is within the [0 ... threads.size] range.
     *
     * @param thread The thread for which the id is to be retrieved.
     * @return The id of the thread, or -1 if the thread is not found.
     */
    fun getThreadId(thread: Thread): ThreadId {
        val descriptor = ThreadDescriptor.getThreadDescriptor(thread)
        return (descriptor?.eventTrackerData as? ThreadHandle)?.id ?: -1
    }

    /**
     * Retrieves the identifier of the current thread.
     * Thread id is within the [0 ... threads.size] range.
     *
     * @return The id of the current thread, or -1 if the current thread is not found.
     */
    fun getCurrentThreadId(): ThreadId {
        val descriptor = ThreadDescriptor.getCurrentThreadDescriptor()
        return (descriptor?.eventTrackerData as? ThreadHandle)?.id ?: -1
    }

    /**
     * Return an iterable sequence of thread ids registered within this scheduler.
     */
    fun getRegisteredThreadIds(): Iterable<ThreadId> =
        (0 until threads.size)

    /**
     * Retrieves all threads registered in the scheduler.
     *
     * @return a map from thread ids to thread instances that are currently registered.
     */
    fun getRegisteredThreads() : ThreadMap<Thread> = mutableThreadMapOf<Thread>().apply {
        for (threadHandle in threads) {
            val thread = threadHandle.descriptor.thread
            if (thread != null) {
                put(threadHandle.id, thread)
            }
        }
    }

    /**
     * Checks if all threads managed by the scheduler have finished execution.
     *
     * @return `true` if all threads are finished, otherwise `false`.
     */
    fun areAllThreadsFinished() =
        threads.all { it.isFinished }

    /**
     * Checks if all threads managed by the scheduler have finished or aborted.
     * @return `true` if all threads are finished or aborted, otherwise `false`.
     */
    fun areAllThreadsFinishedOrAborted() =
        threads.all { it.isFinishedOrAborted }

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
        val threadData = createThreadHandle(threadId, descriptor)
        if (thread is TestThread) {
            check(threadId == thread.threadId)
        }
        check(threadId == _threads.size)
        _threads.add(threadData)
        descriptor.eventTrackerData = threadData
        return threadId
    }

    /**
     * Aborts all threads controlled by the scheduler.
     *
     * @see [ThreadHandle.abortThread]
     */
    fun abortAllThreads() {
        for (threadHandle in threads) {
            if (threadHandle.isFinished) continue
            threadHandle.abortThread()
        }
    }

    /**
     * Aborts all threads controlled by the scheduler, except the current thread.
     *
     * @see [ThreadHandle.abortThread]
     */
    fun abortOtherThreads() {
        val currentThreadHandle = getCurrentThreadHandle()
        for (threadHandle in threads) {
            if (threadHandle.isFinished || threadHandle.id == currentThreadHandle.id) continue
            threadHandle.abortThread()
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
    fun awaitThreadFinish(threadHandle: ThreadHandle, timeoutNano: Long): Long {
        check(threadHandle is ThreadHandleImpl)

        // special handling of Lincheck test threads
        if (threadHandle.descriptor.thread is TestThread) {
            val elapsedTime = threadHandle.spinner.spinWaitTimedUntil(timeoutNano) {
                // TODO: due to limitations of current implementation,
                //   Lincheck test threads sometime end up in ABORTED state,
                //   even though they are actually finished
                threadHandle.isFinishedOrAborted
            }
            return elapsedTime
        }
        val startTime = System.nanoTime()
        val timeoutMs = timeoutNano / 1_000_000
        threadHandle.descriptor.thread?.join(timeoutMs, (timeoutNano % 1_000_000).toInt())
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
        for (threadHandle in threads) {
            val elapsedTime = awaitThreadFinish(threadHandle, remainingTime)
            if (elapsedTime < 0) return -1
            remainingTime -= elapsedTime
        }
        return (timeoutNano - remainingTime)
    }

    /**
     * Resets the thread scheduler by removing all registered threads.
     */
    fun reset() {
        _threads.clear()
    }
}

/**
 * Checks if the thread is currently enabled.
 *
 * @return `true` if the thread is enabled, `false` otherwise.
 */
val ThreadScheduler.ThreadHandle.isEnabled: Boolean get() {
    return (state == ThreadState.ENABLED)
}

/**
 * Checks if the thread is currently schedulable.
 * The thread is schedulable if it is in INITIALIZED or ENABLED state.
 *
 * @return `true` if the thread is schedulable, `false` otherwise.
 */
val ThreadScheduler.ThreadHandle.isSchedulable: Boolean get() {
    val state = this.state
    return (state == ThreadState.INITIALIZED) || (state == ThreadState.ENABLED)
}

/**
 * Checks if the thread is currently blocked.
 *
 * @return `true` if the thread is blocked, `false` otherwise.
 */
val ThreadScheduler.ThreadHandle.isBlocked: Boolean get() {
    return (state == ThreadState.BLOCKED)
}

/**
 * Checks if the thread is currently live-locked.
 *
 * @return `true` if the thread is live-locked, `false` otherwise.
 */
val ThreadScheduler.ThreadHandle.isLiveLocked: Boolean get() {
    return (blockingReason is BlockingReason.LiveLocked)
}

/**
 * Checks if the thread is currently parked.
 *
 * @return `true` if the thread is parked, `false` otherwise.
 */
val ThreadScheduler.ThreadHandle.isParked: Boolean get() {
    return (blockingReason is BlockingReason.Parked)
}

/**
 * Checks if the thread was aborted.
 *
 * @return `true` if the thread is aborted, `false` otherwise.
 */
val ThreadScheduler.ThreadHandle.isAborted: Boolean get() {
    return (state == ThreadState.ABORTED)
}

/**
 * Checks if the thread has finished its execution.
 *
 * @return `true` if the thread is finished, `false` otherwise.
 */
val ThreadScheduler.ThreadHandle.isFinished: Boolean get() {
    return (state == ThreadState.FINISHED)
}

/**
 * Checks if the thread has finished or aborted.
 *
 * @return `true` if the thread is finished or aborted, `false` otherwise.
 */
val ThreadScheduler.ThreadHandle.isFinishedOrAborted: Boolean get() {
    val state = this.state
    return (state == ThreadState.FINISHED) || (state == ThreadState.ABORTED)
}
