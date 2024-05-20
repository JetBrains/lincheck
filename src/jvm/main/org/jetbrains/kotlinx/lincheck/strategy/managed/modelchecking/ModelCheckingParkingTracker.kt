/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking

import org.jetbrains.kotlinx.lincheck.strategy.managed.*

class ModelCheckingParkingTracker(val nThreads: Int, val allowSpuriousWakeUps: Boolean = false) : ParkingTracker {

    /*
     * Stores `true` for the parked threads.
     */
    private val parked = BooleanArray(nThreads) { false }

    override fun park(iThread: Int) {
        parked[iThread] = true
    }

    override fun waitUnpark(iThread: Int): Boolean {
        return isParked(iThread)
    }

    override fun unpark(iThread: Int, unparkingThreadId: Int) {
        parked[unparkingThreadId] = false
    }

    override fun isParked(iThread: Int): Boolean =
        parked[iThread] && !allowSpuriousWakeUps

    override fun reset() {
        parked.fill(false)
    }

}