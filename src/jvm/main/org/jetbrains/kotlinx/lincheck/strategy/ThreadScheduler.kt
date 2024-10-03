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

import org.jetbrains.kotlinx.lincheck.strategy.managed.ForcibleExecutionFinishError
import org.jetbrains.kotlinx.lincheck.util.*


typealias ThreadId = Int

typealias ThreadMap<T> = Map<ThreadId, T>

typealias MutableThreadMap<T> = MutableMap<ThreadId, T>

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
}

open class ThreadScheduler {

    protected class ThreadDescriptor(val id: ThreadId, val thread: Thread) {
        @Volatile var state: ThreadState = ThreadState.INITIALIZED

        @Volatile var blockingReason: BlockingReason? = null

        val spinner: Spinner = Spinner() // TODO: pass nThreads

    }

    private val threads_ = mutableMapOf<ThreadId, ThreadDescriptor>()
    protected val threads: ThreadMap<ThreadDescriptor> get() = threads_

    fun getThreadId(thread: Thread): ThreadId =
        threads.values.find { it.thread == thread }?.id ?: -1

    fun getThread(threadId: ThreadId): Thread? =
        threads[threadId]?.thread

    fun getThreadState(threadId: ThreadId): ThreadState? =
        threads[threadId]?.state

    fun isFinished(threadId: ThreadId) =
        getThreadState(threadId) == ThreadState.FINISHED

    fun areAllThreadsFinished() =
        threads.values.all { it.state == ThreadState.FINISHED }

    fun registerThread(threadId: ThreadId, thread: Thread) {
        val descriptor = ThreadDescriptor(threadId, thread)
        threads_.put(threadId, descriptor).ensureNull()
    }

    fun abortThread(threadId: ThreadId) {
        threads[threadId]?.apply { state = ThreadState.ABORTED }
    }

    fun abortAllThreads() {
        threads.values.forEach { it.state = ThreadState.ABORTED }
    }

    fun finishThread(threadId: ThreadId) {
        threads[threadId]?.apply { state = ThreadState.FINISHED }
    }

    fun reset() {
        threads_.clear()
    }

}