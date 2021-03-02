/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.distributed

import kotlinx.atomicfu.AtomicBooleanArray
import kotlinx.coroutines.sync.Semaphore

/**
 * Stores information about failed nodes and controls that
 * the number of failed nodes does not exceed the specified maximum
 **/
class NodeFailureInfo(
    private val numberOfNodes: Int,
    private val maxNumberOfFailedNodes: Int
) {
    private val failedNodes = AtomicBooleanArray(numberOfNodes)
    private var semaphore = if (maxNumberOfFailedNodes != 0) Semaphore(maxNumberOfFailedNodes, 0) else null

    /**
     * If the total number of failed nodes does not exceed the maximum possible number of failed nodes,
     * sets [iNode] to `failed` and returns true.
     * Otherwise returns false.
     */
    fun trySetFailed(iNode: Int): Boolean {
        if (failedNodes[iNode].value) {
            return true
        }
        if (semaphore?.tryAcquire() != true) {
            return false
        }
        failedNodes[iNode].lazySet(true)
        return true
    }

    /**
     * Returns true if [iNode] is set to `failed`.
     */
    operator fun get(iNode: Int) = failedNodes[iNode].value

    /**
     * Clears `failed` mark for [iNode]
     */
    fun setRecovered(iNode: Int) {
        if (!failedNodes[iNode].value) {
            return
        }
        failedNodes[iNode].lazySet(false)
        semaphore?.release()
    }

    /**
     * Resets to initial state.
     */
    fun reset() {
        repeat(numberOfNodes) {
            failedNodes[it].lazySet(false)
        }
        semaphore = if (maxNumberOfFailedNodes != 0) Semaphore(maxNumberOfFailedNodes, 0) else null
    }
}
