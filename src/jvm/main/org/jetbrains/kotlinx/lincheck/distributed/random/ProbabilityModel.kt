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

package org.jetbrains.kotlinx.lincheck.distributed.random

import org.apache.commons.math3.distribution.PoissonDistribution
import org.jetbrains.kotlinx.lincheck.distributed.DistributedCTestConfiguration
import kotlin.math.max
import kotlin.random.Random

internal class Probability(private val testCfg: DistributedCTestConfiguration<*, *>) {
    companion object {
        const val MEAN_POISSON_DISTRIBUTION = 0.1
        const val MESSAGE_SENT_PROBABILITY = 0.95
        const val MESSAGE_DUPLICATION_PROBABILITY = 0.1
        const val NODE_RECOVERY_PROBABILITY = 0.7
        var failedNodesExpectation = -1
        var networkPartitionsExpectation = 2
        const val SIMULTANEOUS_CRASH_COUNT = 3
        const val DEFAULT_RECOVER_TIMEOUT = 10
    }

    val rand = Random(0)

    private val poissonDistribution = PoissonDistribution(MEAN_POISSON_DISTRIBUTION)

    var nextNumberOfCrashes = 0
    private val numberOfNodes: Int = testCfg.addressResolver.nodeCount
    private var currentErrorPoint = 0
    private var previousNumberOfPoints = 0

    fun duplicationRate(): Int {
        if (!messageIsSent()) {
            return 0
        }
        if (!testCfg.messageDuplication) {
            return 1
        }
        return if (rand.nextDouble(1.0) > MESSAGE_DUPLICATION_PROBABILITY) 1 else 2
    }

    fun poissonProbability(x: Int) = poissonDistribution.probability(x) >= rand.nextDouble(1.0)

    private fun messageIsSent(): Boolean {
        if (testCfg.isNetworkReliable) {
            return true
        }
        return rand.nextDouble(1.0) < MESSAGE_SENT_PROBABILITY
    }

    fun nodeFailed(): Boolean {
        currentErrorPoint++
        if (nextNumberOfCrashes > 0) {
            nextNumberOfCrashes--
            return true
        }
        val r = rand.nextDouble(1.0)
        val p = nodeFailProbability()
        if (r >= p) return false
        nextNumberOfCrashes = rand.nextInt(0, SIMULTANEOUS_CRASH_COUNT)
        return true
    }

    fun nodeRecovered(): Boolean = rand.nextDouble(1.0) < NODE_RECOVERY_PROBABILITY

    private fun nodeFailProbability(): Double {
        //return NODE_FAIL_PROBABILITY
        return if (previousNumberOfPoints == 0) {
            0.0
        } else {
            val q = failedNodesExpectation.toDouble() / previousNumberOfPoints
            return q
            /*return if (testCfg.supportRecovery == CrashMode.NO_RECOVER) {
                q / (previousNumberOfPoints - (currentErrorPoint - 1) * q)
            } else {
                q / previousNumberOfPoints
            }*/
        }
    }

    fun isNetworkPartition(): Boolean {
        if (previousNumberOfPoints == 0) return false
        val q = networkPartitionsExpectation.toDouble() / numberOfNodes
        val p = q / previousNumberOfPoints
        val r = rand.nextDouble(1.0)
        return r < p
    }

    fun partition(nodes: List<Int>, limit: Int): List<Int> {
        if (limit == 0) return emptyList()
        val count = rand.nextInt(limit)
        return nodes.shuffled(rand).take(count)
    }

    fun reset(failedNodesExp: Int = 0) {
        if (failedNodesExpectation == -1) failedNodesExpectation = failedNodesExp
        previousNumberOfPoints = max(previousNumberOfPoints, currentErrorPoint)
        currentErrorPoint = 0
    }

    fun recoverTimeout(maxTimeout: Int): Int = rand.nextInt(1, maxTimeout * 2)
}