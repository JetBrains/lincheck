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
 * Tracks parking operations.
 */
interface ParkingTracker {

    /**
     * Parks the specified thread.
     *
     * @param iThread the id of the thread to be parked
     */
    fun park(iThread: Int)

    /**
     * Waits for the specified thread to be unparked.
     *
     * @param iThread the id of the thread to check for parking status.
     * @return true if the thread should continue waiting,
     *   false if it was already unparked.
     */
    fun waitUnpark(iThread: Int): Boolean

    /**
     * Unparks a thread, allowing it to continue execution.
     *
     * @param iThread the id of the thread performing unpark operation.
     * @param unparkedThreadId the id of the thread to unpark.
     */
    fun unpark(iThread: Int, unparkedThreadId: Int)

    /**
     * Checks whether a thread with the specified id is currently parked or not.
     *
     * @param iThread the id of the thread to check for parking status.
     * @return true if the thread is parked, false otherwise.
     */
    fun isParked(iThread: Int): Boolean

    /**
     * Resets the state of the parking tracker.
     */
    fun reset()

}