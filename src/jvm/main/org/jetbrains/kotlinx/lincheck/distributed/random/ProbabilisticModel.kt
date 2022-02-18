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

import org.jetbrains.kotlinx.lincheck.distributed.CrashMode.FINISH_ON_CRASH
import org.jetbrains.kotlinx.lincheck.distributed.DistributedCTestConfiguration
import org.jetbrains.kotlinx.lincheck.distributed.Task
import kotlin.math.max
import kotlin.random.Random


interface DecisionModel {
    fun messageRate(): Int

    fun geometricProbability(x: Int): Boolean

    fun nodeCrashed(iNode: Int): Boolean

    fun nodeRecovered(): Boolean

    fun isPartition(iNode: Int): Boolean

    fun choosePartition(nodes: List<Int>, limit: Int): List<Int>

    fun recoverTimeout(maxTimeout: Int): Int

    fun chooseTask(tasks: List<Task>): Task

    fun reset()
}

internal class DecisionInfo {
    private var messageRateCall = 0
    private val messageRate = mutableMapOf<Int, Int>()
    private var crashCall = 0
    private val crashes = mutableSetOf<Int>()
    private var recoverCall = 0
    private val recovers = mutableSetOf<Int>()
    private var partitionCall = 0
    private val partitions = mutableSetOf<Int>()
    private val partitionChoices = mutableListOf<List<Int>>()
    private var partitionChoicePointer = 0

    fun setMessageRate(rate: Int) {
        messageRateCall++
        if (rate != 1) messageRate[messageRateCall] = rate
    }

    fun setCrash(isCrash: Boolean) {
        crashCall++
        if (isCrash) crashes.add(crashCall)
    }

    fun setRecover(isRecover: Boolean) {
        recoverCall++
        if (isRecover) recovers.add(recoverCall)
    }

    fun setPartition(isPartition: Boolean) {
        partitionCall++
        if (isPartition) partitions.add(partitionCall)
    }

    fun setPartitionChoice(partition: List<Int>) {
        partitionChoices.add(partition)
    }

    fun resetCounters() {
        messageRateCall = 0
        crashCall = 0
        recoverCall = 0
        partitionCall = 0
    }

    fun getMessageRate(): Int {
        messageRateCall++
        return messageRate.getOrDefault(messageRateCall, 1)
    }

    fun getCrash(): Boolean {
        crashCall++
        return crashCall in crashes
    }

    fun getRecover(): Boolean {
        recoverCall++
        return recoverCall in recovers
    }

    fun getPartition(): Boolean {
        partitionCall++
        return partitionCall in partitions
    }

    fun getPartitionChoice(): List<Int> {
        return partitionChoices[partitionChoicePointer++]
    }
}

private class GeometricProbability(private val p: Double) {
    private val precalculated = mutableMapOf<Int, Double>()

    fun probability(x: Int) : Double {
        if (x in precalculated) return precalculated[x]!! * p
        if (x == 0) {
            precalculated[x] = 1.0
            return p
        }
        val prev = probability(x / 2)
        val res = if (x % 2 == 0) {
            prev * prev
        } else {
            prev * prev * (1 - p)
        }
        precalculated[x] = res
        return res * p
    }
}

/**
 * Probability model which generates random events which happen during the execution.
 */
internal class ProbabilisticModel(private val testCfg: DistributedCTestConfiguration<*>) : DecisionModel {
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
    private val geometricProbability = GeometricProbability(MEAN_GEOMETRIC_DISTRIBUTION)
    private val crashExpectation: Int = crashedNodesExpectation.get()
    private val partitionExpectation: Int = networkPartitionExpectation.get()

    private val rand = Random(0)

    private var nextNumberOfCrashes = 0
    private val nodeCount: Int = testCfg.addressResolver.nodeCount
    private val currentErrorPoint = Array(testCfg.addressResolver.nodeCount) { 0 }
    private val previousNumberOfPoints = Array(testCfg.addressResolver.nodeCount) { 0 }
    private val addressResolver = testCfg.addressResolver

    var decisionInfo = DecisionInfo()

    override fun messageRate(): Int = run {
        if (!isMessageSent()) 0
        else duplicationRate()
    }.also { decisionInfo.setMessageRate(it) }


    /**
     * Returns how many times the message should be sent (from 0 to 2).
     */
    private fun duplicationRate(): Int {
        if (!testCfg.messageDuplication) return 1
        return if (rand.nextDouble(1.0) > MESSAGE_DUPLICATION_PROBABILITY) 1 else 2
    }

    /**
     * Returns the Poisson probability for value [x].
     * It used in [DistributedRandomStrategy][DistributedRandomStrategy] to decide which [time tasks][org.jetbrains.kotlinx.lincheck.distributed.TimeTask]
     * will be considered to be chosen for the next iteration.
     */
    override fun geometricProbability(x: Int): Boolean {
        /*val n = rand.nextInt(Int.MAX_VALUE)
        println("$x, $n, ${-(log2(n.toFloat() / Int.MAX_VALUE))}, ${log2(Int.MAX_VALUE.toFloat()) - log2(n.toFloat())}")
        // f(2) = 1/2 > -(log2(
        return 1f / x > -(log2(n.toFloat() / Int.MAX_VALUE))*/
        return geometricProbability.probability(x) >= rand.nextDouble(1.0)
    }

    /**
     * Returns if the message should be sent.
     */
    private fun isMessageSent(): Boolean {
        if (testCfg.messageLoss) {
            return true
        }
        return rand.nextDouble(1.0) < MESSAGE_SENT_PROBABILITY
    }

    /**
     * Returns if the node should fail.
     */
    override fun nodeCrashed(iNode: Int) = run {
        currentErrorPoint[iNode]++
        if (nextNumberOfCrashes > 0) {
            nextNumberOfCrashes--
            return@run true
        }
        val r = rand.nextDouble(1.0)
        val p = nodeFailProbability(iNode)
        if (r >= p) return@run false
        nextNumberOfCrashes = rand.nextInt(0, SIMULTANEOUS_CRASH_COUNT)
        return@run true
    }.also { decisionInfo.setCrash(it) }

    /**
     * Returns if the node should be recovered.
     */
    override fun nodeRecovered(): Boolean =
        run { rand.nextDouble(1.0) < NODE_RECOVERY_PROBABILITY }.also { decisionInfo.setRecover(it) }

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
    override fun isPartition(iNode: Int): Boolean = run {
        if (previousNumberOfPoints[iNode] == 0) return false
        val q = partitionExpectation.toDouble() / nodeCount
        val p = q / previousNumberOfPoints[iNode]
        val r = rand.nextDouble(1.0)
        r < p
    }.also { decisionInfo.setPartition(it) }

    /**
     * Chooses randomly nodes which will be included in the partition.
     */
    override fun choosePartition(nodes: List<Int>, limit: Int): List<Int> = run {
        if (limit == 0) return emptyList()
        val count = rand.nextInt(limit)
        nodes.shuffled(rand).take(count)
    }.also { decisionInfo.setPartitionChoice(it) }

    /**
     * Resets the probability to the initial state.
     */
    override fun reset() {
        previousNumberOfPoints.indices.forEach {
            previousNumberOfPoints[it] = max(previousNumberOfPoints[it], currentErrorPoint[it])
        }
        currentErrorPoint.fill(0)
        decisionInfo = DecisionInfo()
    }

    /**
     * Returns the recover timeout for the node crash or network partition.
     */
    override fun recoverTimeout(maxTimeout: Int): Int = rand.nextInt(1, maxTimeout * 2)
    override fun chooseTask(tasks: List<Task>): Task = tasks.random(rand)
}

internal class DeterministicModel(private val decisionInfo: DecisionInfo) : DecisionModel {
    init {
        decisionInfo.resetCounters()
    }

    override fun messageRate(): Int = decisionInfo.getMessageRate()

    override fun geometricProbability(x: Int): Boolean = error("Should not be called")

    override fun nodeCrashed(iNode: Int): Boolean = decisionInfo.getCrash()

    override fun nodeRecovered(): Boolean = decisionInfo.getRecover()

    override fun isPartition(iNode: Int): Boolean = decisionInfo.getPartition()

    override fun choosePartition(nodes: List<Int>, limit: Int): List<Int> = decisionInfo.getPartitionChoice()

    override fun recoverTimeout(maxTimeout: Int): Int = error("Should not be called")

    override fun chooseTask(tasks: List<Task>): Task = error("Should not be called")

    override fun reset() {
    }
}