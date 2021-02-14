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

package org.jetbrains.kotlinx.lincheck.distributed.stress

import kotlinx.atomicfu.AtomicBooleanArray
import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.distributed.DistributedCTestConfiguration
import java.util.concurrent.ThreadLocalRandom

class Probability(private val testCfg: DistributedCTestConfiguration<*, *>, val numberOfNodes: Int) {
    fun duplicationRate(): Int {
        if (!testCfg.messageDuplication) {
            return 1
        }
        return if (ThreadLocalRandom.current().nextDouble(1.0) < 0.8) 1 else 2
    }

    fun messageIsSent(): Boolean {
        if (testCfg.isNetworkReliable) {
            return true
        }
        return ThreadLocalRandom.current().nextDouble(1.0) < 0.95
    }

    fun nodeFailed(failures: FailureStatistics): Boolean {
        if (failures.isFull()) {
            return false
        }
        val failProb = failures.maxNumberOfFailedNodes * 0.2 / (numberOfNodes * testCfg.actorsPerThread)
        return ThreadLocalRandom.current().nextDouble(0.0, 1.0) < failProb
    }
}


class FailureStatistics(
    val numberOfNodes: Int, val maxNumberOfFailedNodes: Int
) {
    private val failedNodes = AtomicBooleanArray(numberOfNodes)
    private val failedNodesCnt = atomic(0)

    val numberOfFailedNodes = failedNodesCnt.value

    operator fun set(node: Int, value: Boolean) {
        if (failedNodes[node].value == value) {
            return
        }
        failedNodes[node].lazySet(value)
        if (value) {
            failedNodesCnt.incrementAndGet()
        } else {
            failedNodesCnt.decrementAndGet()
        }
    }

    fun clear() {
        for (i in 0 until numberOfNodes) {
            failedNodes[i].lazySet(false)
        }
        failedNodesCnt.lazySet(0)
    }

    operator fun get(node: Int) = failedNodes[node].value

    fun isFull(): Boolean = maxNumberOfFailedNodes == numberOfFailedNodes
}
