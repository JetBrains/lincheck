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

import org.jetbrains.kotlinx.lincheck.util.*

enum class ThreadState {
    INITIALIZED,
    ENABLED,
    BLOCKED,
    ABORTED,
    FINISHED,
}

enum class BlockingReason {
    LOCKED,
    WAITING,
    SUSPENDED,
    PARKED,
    THREAD_JOIN,
}

open class ThreadScheduler {

    private val threads_ = mutableThreadMapOf<ThreadDescriptor>()
    protected val threads: ThreadMap<ThreadDescriptor> get() = threads_

    val nThreads: Int get() =
        threads.size

    protected open class ThreadDescriptor(
        val id: ThreadId,
        val thread: Thread,
        val scheduler: ThreadScheduler,
    ) {
        @Volatile var state: ThreadState = ThreadState.INITIALIZED

        @Volatile var blockingReason: BlockingReason? = null

        val spinner: Spinner = Spinner { scheduler.threads.size }
    }

    protected open fun createThreadDescriptor(id: ThreadId, thread: Thread): ThreadDescriptor {
        return ThreadDescriptor(id, thread, this)
    }

    fun getRegisteredThreads(): List<Thread> =
        threads.values.map { it.thread }

    fun getThreadId(thread: Thread): ThreadId =
        threads.values.find { it.thread == thread }?.id ?: -1

    fun getThread(threadId: ThreadId): Thread? =
        threads[threadId]?.thread

    fun getThreadState(threadId: ThreadId): ThreadState? =
        threads[threadId]?.state

    fun getBlockingReason(threadId: ThreadId): BlockingReason? =
        threads[threadId]?.blockingReason

    fun isEnabled(threadId: ThreadId) =
        getThreadState(threadId) == ThreadState.ENABLED

    fun isSchedulable(threadId: ThreadId): Boolean {
        val state = threads[threadId]?.state ?: return false
        return (state == ThreadState.INITIALIZED) || (state == ThreadState.ENABLED)
    }

    fun isBlocked(threadId: ThreadId) =
        getThreadState(threadId) == ThreadState.BLOCKED

    fun isFinished(threadId: ThreadId) =
        getThreadState(threadId) == ThreadState.FINISHED

    fun areAllThreadsFinished() =
        threads.values.all { it.state == ThreadState.FINISHED }

    fun registerThread(thread: Thread): ThreadId {
        val threadId = threads.size
        val descriptor = createThreadDescriptor(threadId, thread)
        threads_.put(threadId, descriptor).ensureNull()
        return threadId
    }

    fun startThread(threadId: ThreadId) {
        threads[threadId]!!.apply {
            check(state == ThreadState.INITIALIZED)
            state = ThreadState.ENABLED
        }
    }

    fun blockThread(threadId: ThreadId, reason: BlockingReason) {
        threads[threadId]!!.apply {
            blockingReason = reason
            state = ThreadState.BLOCKED
        }
    }

    fun unblockThread(threadId: ThreadId) {
        threads[threadId]!!.apply {
            state = ThreadState.ENABLED
            blockingReason = null
        }
    }

    fun abortThread(threadId: ThreadId) {
        threads[threadId]!!.apply {
            state = ThreadState.ABORTED
        }
    }

    fun abortAllThreads() {
        threads.values.forEach {
            it.state = ThreadState.ABORTED
        }
    }

    fun finishThread(threadId: ThreadId) {
        threads[threadId]!!.apply {
            state = ThreadState.FINISHED
        }
    }

    fun reset() {
        threads_.clear()
    }

}