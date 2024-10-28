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
import sun.nio.ch.lincheck.Injections


/**
 * [ManagedThreadScheduler] is responsible for managing the execution of threads
 * ensuring that at any given moment, only a single registered thread is allowed to run.
 *
 * To achieve this goal, the scheduler relies on cooperation from the [ManagedStrategy],
 * in particular, that the strategy correctly places [awaitTurn] calls
 * to artificially block a thread from running until it is scheduled.
 *
 * @see [ThreadScheduler]
 */
class ManagedThreadScheduler : ThreadScheduler() {

    /**
     * Represents the id of the currently scheduled thread.
     *
     * In managed strategy the invariant is that at each point of time
     * only a single registered thread is allowed to run.
     * This variable stores the id of the said thread.
     *
     * This variable is marked as `@Volatile` to ensure visibility of
     * its latest value across different threads.
     */
    @Volatile
    var scheduledThreadId: Int = 0
        private set

    /**
     * Checks if the current thread is the one scheduled to run.
     *
     * @return `true` if the current thread is the one scheduled to run; `false` otherwise.
     */
    fun isCurrentThreadScheduled(): Boolean {
        return scheduledThreadId == getCurrentThreadId()
    }

    /**
     * Schedules the specified thread to be run.
     *
     * @param threadId The identifier of the thread to be scheduled.
     */
    fun scheduleThread(threadId: Int) {
        scheduledThreadId = threadId
    }

    /**
     * Aborts the currently running thread.
     * Throws [ThreadAbortedError] to indicate that the thread has been aborted.
     *
     * @return Nothing as this method always throws [ThreadAbortedError].
     */
    fun abortCurrentThread(): Nothing {
        val threadId = getCurrentThreadId()
        val threadData = threads[threadId]!!
        if (threadData.state != ThreadState.ABORTED) {
           check(threadId == scheduledThreadId)
        }
        threads[threadId]!!.state = ThreadState.ABORTED
        raiseThreadAbortError()
    }

    /**
     * Waits until the specified thread is chosen to continue the execution.
     *
     * @param threadId The identifier of the thread whose turn to wait for.
     * @throws ThreadAbortedError if the thread was aborted.
     */
    fun awaitTurn(threadId: ThreadId) {
        check(threadId == getCurrentThreadId())
        val threadData = threads[threadId]!!
        threadData.spinner.spinWaitUntil {
            if (threadData.state == ThreadState.ABORTED) {
                raiseThreadAbortError()
            }
            scheduledThreadId == threadId
        }
    }

    private fun raiseThreadAbortError(): Nothing {
        /* TODO: currently we cannot implement it this way,
         *   one of the reasons being because of the how monitors support is implemented,
         *   in testing code, monitorenter/monitorexit are replaced with the strategy lock/unlock callbacks,
         *   after we throw the abort exception, some finally blocks still might be executed,
         *   these finally blocks may contain monitorexit instruction,
         *   which this time (outside testing code) will actually be executed,
         *   resulting in `IllegalMonitorStateException`
         */
        // exit the testing code in case of aborting
        // val descriptor = Injections.getCurrentThreadDescriptor()!!
        // descriptor.leaveTestingCode()

        // raise the exception
        throw ThreadAbortedError
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