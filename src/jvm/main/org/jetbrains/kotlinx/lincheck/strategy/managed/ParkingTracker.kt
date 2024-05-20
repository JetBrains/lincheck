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

    fun park(iThread: Int)

    fun waitUnpark(iThread: Int): Boolean

    fun unpark(iThread: Int, unparkedThreadId: Int)

    fun isParked(iThread: Int): Boolean

    fun reset()

}