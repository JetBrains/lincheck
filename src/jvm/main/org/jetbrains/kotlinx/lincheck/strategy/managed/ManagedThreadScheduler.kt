/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.util.*

class ManagedThreadScheduler : ThreadScheduler() {

    /**
     * Represents the ID of the currently scheduled thread.
     *
     * In managed strategy the invariant is that at each point of time
     * only a single registered thread is allowed to run.
     * This variable stores the id of the said thread.
     *
     * This variable is marked as `@Volatile` to ensure visibility of
     * its latest value across different threads.
     */
    @Volatile
    var currentThreadId: Int = 0
        private set

    fun isCurrentThreadScheduled(): Boolean {
        return currentThreadId == getThreadId(Thread.currentThread())
    }

    fun setCurrentThread(threadId: Int) {
        currentThreadId = threadId
    }

    fun abortCurrentThread(): Nothing {
        check(isCurrentThreadScheduled())
        val threadId = currentThreadId
        threads[threadId]!!.state = ThreadState.ABORTED
        throw ThreadAbortedError
    }

    /**
     * Waits until the specified thread is chosen to continue the execution.
     *
     * @param threadId The identifier of the thread whose turn to wait for.
     * @throws ThreadAbortedError if the thread was aborted.
     */
    fun awaitTurn(threadId: ThreadId) {
        check(threadId == getThreadId(Thread.currentThread()))
        val descriptor = threads[threadId]!!
        descriptor.spinner.spinWaitUntil {
            if (descriptor.state == ThreadState.ABORTED)
                throw ThreadAbortedError
            currentThreadId == threadId
        }
    }

}

/**
 * This exception is used to abort the execution correctly for managed strategies,
 * for instance, in case of a deadlock.
 */
internal object ThreadAbortedError : Error() {
    // do not create a stack trace -- it simply can be unsafe
    override fun fillInStackTrace() = this

    private fun readResolve(): Any = ThreadAbortedError
}