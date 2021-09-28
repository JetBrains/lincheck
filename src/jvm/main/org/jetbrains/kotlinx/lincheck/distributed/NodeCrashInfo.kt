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

import java.lang.Integer.min
import java.util.*
import kotlin.random.Random

internal abstract class CrashInfo<M, L>(
    protected val testCfg: DistributedCTestConfiguration<M, L>,
    protected val random: Random
) {
    companion object {
        fun <M, L> createCrashInfo(testCfg: DistributedCTestConfiguration<M, L>, random: Random): CrashInfo<M, L> =
            CrashInfoHalves(testCfg, random)
    }

    protected val addressResolver = testCfg.addressResolver
    protected val maxNumberOfFailedNodes = testCfg.maxNumberOfFailedNodes(addressResolver.totalNumberOfNodes)
    protected val failedNode = Array(addressResolver.totalNumberOfNodes) {
        false
    }
    protected var unavailableNodeCount = 0

    fun remainedNodeCount() = maxNumberOfFailedNodes - unavailableNodeCount

    abstract fun canSend(from: Int, to: Int): Boolean

    operator fun get(iNode: Int) = failedNode[iNode]

    abstract fun crashNode(iNode: Int)

    abstract fun canCrash(iNode: Int): Boolean

    abstract fun recoverNode(iNode: Int)

    abstract fun addPartition(firstNode: Int, secondNode: Int)

    abstract fun removePartition(firstNode: Int, secondNode: Int)
}

internal class CrashInfoHalves<M, L>(testCfg: DistributedCTestConfiguration<M, L>, random: Random) :
    CrashInfo<M, L>(testCfg, random) {
    private val firstPartition = mutableSetOf<Int>()
    private val secondPartition = (0 until testCfg.addressResolver.totalNumberOfNodes).toMutableSet()


    override fun canSend(from: Int, to: Int) = !failedNode[from] &&
            !failedNode[to] &&
            (firstPartition.containsAll(listOf(from, to))
                    || secondPartition.containsAll(listOf(from, to)))

    override fun crashNode(iNode: Int) {
        check(!failedNode[iNode])
        failedNode[iNode] = true
        unavailableNodeCount++
    }

    override fun canCrash(iNode: Int): Boolean {
        if (failedNode[iNode]) return false
        if (maxNumberOfFailedNodes == 0) return false
        val type = addressResolver[iNode]
        val maxFailedForType = addressResolver.maxNumberOfCrashesForNode(iNode)
        if (maxFailedForType == 0) return false
        if (maxFailedForType != addressResolver[type]!!.size) {
            val failedNodesForType =
                failedNode.filterIndexed { index, value -> addressResolver[index] == type && value }.size + min(
                    firstPartition.filter { addressResolver[it] == type }.size,
                    secondPartition.filter { addressResolver[it] == type }.size
                )
            return maxNumberOfFailedNodes > unavailableNodeCount && failedNodesForType < maxFailedForType
        }
        return maxNumberOfFailedNodes > unavailableNodeCount
    }

    override fun recoverNode(iNode: Int) {
        //println("Before recover ${failedNode[iNode]}")
        check(failedNode[iNode])
        failedNode[iNode] = false
        unavailableNodeCount--
    }

    override fun addPartition(firstNode: Int, secondNode: Int) {

    }

    override fun removePartition(firstNode: Int, secondNode: Int) {
        TODO("Not yet implemented")
    }
}
