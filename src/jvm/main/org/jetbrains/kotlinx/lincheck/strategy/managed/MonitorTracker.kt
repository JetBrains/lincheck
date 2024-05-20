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

    fun acquireMonitor(iThread: Int, monitor: Any): Boolean

    fun releaseMonitor(iThread: Int, monitor: Any)

    fun waitOnMonitor(iThread: Int, monitor: Any): Boolean

    fun notify(iThread: Int, monitor: Any, notifyAll: Boolean)

    fun isWaiting(iThread: Int): Boolean

    fun reset()

}