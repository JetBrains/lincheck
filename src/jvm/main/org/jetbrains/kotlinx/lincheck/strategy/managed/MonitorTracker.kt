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

/**
 * Tracks synchronization operations on the monitors (intrinsic locks).
 */
interface MonitorTracker {

    /**
     * Registers a thread with the given id in the monitor tracker.
     *
     * @param threadId the id of the thread to register.
     */
    fun registerThread(threadId: Int)

    /**
     * Attempts to acquire a monitor for a thread.
     *
     * @param threadId the id of the thread performing acquisition.
     * @param monitor the monitor object to acquire.
     * @return true if the monitor was successfully acquired, false otherwise.
     */
    fun acquireMonitor(threadId: Int, monitor: Any): Boolean

    /**
     * Releases a monitor previously acquired by a thread.
     *
     * @param threadId the id of the thread releasing the monitor.
     * @param monitor the monitor object to release.
     * @return true if the thread completely released the monitor,
     *   false otherwise (e.g., in case of lock re-entrance).
     */
    fun releaseMonitor(threadId: Int, monitor: Any): Boolean

    /**
     * Retrieves a list of threads that wait to acquire the specified monitor.
     *
     * @param monitor the monitor object.
     * @return a list of thread IDs waiting to acquire the given monitor.
     */
    fun acquiringThreads(monitor: Any): List<Int>

    /**
     * Waits for a monitor to be notified by another thread.
     *
     * @param threadId the id of the thread waiting on the monitor.
     * @param monitor the monitor object to wait on.
     * @return true if the thread should continue waiting on the monitor,
     *   false if the monitor was notified.
     */
    fun waitOnMonitor(threadId: Int, monitor: Any): Boolean

    /**
     * Notifies a monitor object.
     *
     * @param threadId the id of the thread performing notification.
     * @param monitor the notified monitor object.
     * @param notifyAll true if all threads waiting on the monitor should be notified,
     *   false if only one thread should be notified.
     */
    fun notify(threadId: Int, monitor: Any, notifyAll: Boolean)

    /**
     * Interrupts a thread's waiting on a monitor.
     *
     * @param threadId the ID of the thread whose waiting state should be interrupted.
     * @throws IllegalStateException if the thread does not wait on some monitor.
     */
    fun interruptWait(threadId: Int)

    /**
     * Checks if a thread with the given ID is waiting on some monitor.
     *
     * @param threadId the id of the thread to check.
     * @return true if the thread with the given id is waiting on a monitor, false otherwise.
     */
    fun isWaiting(threadId: Int): Boolean

    /**
     * Resets the state of the monitor tracker.
     */
    fun reset()

}