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

import org.apache.commons.math3.distribution.GeometricDistribution
import org.jetbrains.kotlinx.lincheck.distributed.CrashMode.FINISH_ON_CRASH
import org.jetbrains.kotlinx.lincheck.distributed.DistributedCTestConfiguration
import kotlin.math.max
import kotlin.random.Random

/**
 * Probability model which generates random events which happen during the execution.
 */
internal class ProbabilityModel(private val testCfg: DistributedCTestConfiguration<*>) {
    companion object {
        private const val MEAN_GEOMETRIC_DISTRIBUTION = 0.9
        private const val MESSAGE_SENT_PROBABILITY = 0.95
        private const val MESSAGE_DUPLICATION_PROBABILITY = 0.1
        private const val NODE_RECOVERY_PROBABILITY = 0.7
        private const val SIMULTANEOUS_CRASH_COUNT = 3
        const val DEFAULT_RECOVER_TIMEOUT = 10
        private const val DEFAULT_CRASH_EXPECTATION = 4
        private const val DEFAULT_PARTITION_EXPECTATION = 2

        val crashedNodesExpectation: ThreadLocal<Int> = ThreadLocal.withInitial { DEFAULT_CRASH_EXPECTATION }
        val networkPartitionExpectation: ThreadLocal<Int> = ThreadLocal.withInitial { DEFAULT_PARTITION_EXPECTATION }
    }

    private val crashExpectation: Int = crashedNodesExpectation.get()
    private val partitionExpectation: Int = networkPartitionExpectation.get()

    val rand = Random(0)
    private val geometricDistribution = GeometricDistribution(MEAN_GEOMETRIC_DISTRIBUTION)

    private var nextNumberOfCrashes = 0
    private val nodeCount: Int = testCfg.addressResolver.nodeCount
    private val currentErrorPoint = Array(testCfg.addressResolver.nodeCount) { 0 }
    private val previousNumberOfPoints = Array(testCfg.addressResolver.nodeCount) { 0 }
    private val addressResolver = testCfg.addressResolver

    /**
     * Returns how many times the message should be sent (from 0 to 2).
     */
    fun duplicationRate(): Int {
        if (!messageIsSent()) {
            return 0
        }
        if (!testCfg.messageDuplication) {
            return 1
        }
        return if (rand.nextDouble(1.0) > MESSAGE_DUPLICATION_PROBABILITY) 1 else 2
    }

    /**
     * Returns the Poisson probability for value [x].
     * It used in [DistributedRandomStrategy][DistributedRandomStrategy] to decide which [time tasks][org.jetbrains.kotlinx.lincheck.distributed.TimeTask]
     * will be considered to be chosen for the next iteration.
     */
    fun geometricProbability(x: Int) = geometricDistribution.probability(x) >= rand.nextDouble(1.0)

    /**
     * Returns if the message should be sent.
     */
    private fun messageIsSent(): Boolean {
        if (testCfg.messageLoss) {
            return true
        }
        return rand.nextDouble(1.0) < MESSAGE_SENT_PROBABILITY
    }

    /**
     * Returns if the node should fail.
     */
    fun nodeFailed(iNode: Int): Boolean {
        currentErrorPoint[iNode]++
        if (nextNumberOfCrashes > 0) {
            nextNumberOfCrashes--
            return true
        }
        val r = rand.nextDouble(1.0)
        val p = nodeFailProbability(iNode)
        if (r >= p) return false
        nextNumberOfCrashes = rand.nextInt(0, SIMULTANEOUS_CRASH_COUNT)
        return true
    }

    /**
     * Returns if the node should be recovered.
     */
    fun nodeRecovered(): Boolean = rand.nextDouble(1.0) < NODE_RECOVERY_PROBABILITY

    /**
     * Generates node fail probability.
     */
    private fun nodeFailProbability(iNode: Int): Double {
        return if (previousNumberOfPoints[iNode] == 0) {
            0.0
        } else {
            val q =
                crashExpectation.toDouble() / addressResolver.size(iNode)
            return if (addressResolver.crashTypeForNode(iNode) == FINISH_ON_CRASH) {
                q / (previousNumberOfPoints[iNode] - (currentErrorPoint[iNode] - 1) * q)
            } else {
                q / previousNumberOfPoints[iNode]
            }
        }
    }

    /**
     * Returns if the network partition should be added here.
     */
    fun isNetworkPartition(iNode: Int): Boolean {
        if (previousNumberOfPoints[iNode] == 0) return false
        val q = partitionExpectation.toDouble() / nodeCount
        val p = q / previousNumberOfPoints[iNode]
        val r = rand.nextDouble(1.0)
        return r < p
    }

    /**
     * Chooses randomly nodes which will be included in the partition.
     */
    fun partition(nodes: List<Int>, limit: Int): List<Int> {
        if (limit == 0) return emptyList()
        val count = rand.nextInt(limit)
        return nodes.shuffled(rand).take(count)
    }

    /**
     * Resets the probability to the initial state.
     */
    fun reset() {
        previousNumberOfPoints.indices.forEach {
            previousNumberOfPoints[it] = max(previousNumberOfPoints[it], currentErrorPoint[it])
        }
        currentErrorPoint.fill(0)
    }

    /**
     * Returns the recover timeout for the node crash or network partition.
     */
    fun recoverTimeout(maxTimeout: Int): Int = rand.nextInt(1, maxTimeout * 2)
}