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

import org.jetbrains.kotlinx.lincheck.distributed.DistributedCTestConfiguration

abstract class NodeCrashInfo(
    protected val testCfg: DistributedCTestConfiguration<*, *>,
    protected val context: DistributedRunnerContext<*, *>,
    val numberOfFailedNodes: Int,
    val failedNodes: List<Boolean>
) {
    companion object {
        fun initialInstance(
            testCfg: DistributedCTestConfiguration<*, *>,
            context: DistributedRunnerContext<*, *>
        ): NodeCrashInfo {
            val numberOfNodes = context.addressResolver.totalNumberOfNodes
            val failedNodes = List(numberOfNodes) { false }
            val partitions = listOf(emptySet(), (0 until numberOfNodes).toSet())
            return NodeCrashInfoHalves(testCfg, context, 0, partitions, failedNodes, 0)
        }
    }

    val maxNumberOfFailedNodes = testCfg.maxNumberOfFailedNodes(context.addressResolver.totalNumberOfNodes)

    val remainedNodes = maxNumberOfFailedNodes - numberOfFailedNodes

    abstract fun canSend(a: Int, b: Int): Boolean

    operator fun get(a: Int) = failedNodes[a]

    abstract fun crashNode(iNode: Int): NodeCrashInfo?

    abstract fun recoverNode(iNode: Int): NodeCrashInfo

    abstract fun setNetworkPartition(): NodeCrashInfo?

    abstract fun recoverNetworkPartition(): NodeCrashInfo
}


class NodeCrashInfoHalves(
    testCfg: DistributedCTestConfiguration<*, *>,
    context: DistributedRunnerContext<*, *>,
    numberOfFailedNodes: Int,
    val partitions: List<Set<Int>>,
    failedNodes: List<Boolean>,
    val partitionCount: Int
) : NodeCrashInfo(testCfg, context, numberOfFailedNodes, failedNodes) {

    override fun canSend(a: Int, b: Int): Boolean {
        return (partitions[0].contains(a) && partitions[0].contains(b) ||
                partitions[1].contains(a) && partitions[1].contains(b))
    }

    override fun crashNode(iNode: Int): NodeCrashInfoHalves? {
        if (numberOfFailedNodes == maxNumberOfFailedNodes ||
            failedNodes[iNode]
        ) return null
        val newFailedNodes = failedNodes.toMutableList()
        newFailedNodes[iNode] = true
        return NodeCrashInfoHalves(
            testCfg,
            context, numberOfFailedNodes + 1, partitions, newFailedNodes, partitionCount
        )
    }

    override fun recoverNode(iNode: Int): NodeCrashInfoHalves {
        check(failedNodes[iNode])
        val newFailedNodes = failedNodes.toMutableList()
        newFailedNodes[iNode] = false
        return NodeCrashInfoHalves(
            testCfg,
            context, numberOfFailedNodes - 1, partitions, newFailedNodes, partitionCount
        )
    }

    override fun setNetworkPartition(): NodeCrashInfoHalves? {
        if (partitions[0].isNotEmpty() || numberOfFailedNodes == maxNumberOfFailedNodes) return null
        val possiblePartitionSize = maxNumberOfFailedNodes - numberOfFailedNodes
        val rand = Probability.rand.get()
        val partitionSize = rand.nextInt(1, possiblePartitionSize + 1)
        check(partitionSize > 0)
        val nodesInPartition = failedNodes.mapIndexed { index, b -> index to b }
            .filter { !it.second }.map { it.first }.shuffled(rand).take(partitionSize).toSet()
        check(nodesInPartition.isNotEmpty())
        val anotherPartition = (failedNodes.indices).filter { it !in nodesInPartition }.toSet()
        val newNumberOfFailedNodes = numberOfFailedNodes + partitionSize
        return NodeCrashInfoHalves(
            testCfg, context, newNumberOfFailedNodes, listOf(nodesInPartition, anotherPartition),
            failedNodes, partitionCount + 1
        )
    }

    override fun recoverNetworkPartition(): NodeCrashInfoHalves {
        if (partitions[0].isEmpty()) return this
        val partitionSize = partitions[0].size
        return NodeCrashInfoHalves(
            testCfg, context, numberOfFailedNodes - partitionSize, listOf(emptySet(), failedNodes.indices.toSet()),
            failedNodes, partitionCount
        )
    }
}

class NodeCrashInfoSingle(
    testCfg: DistributedCTestConfiguration<*, *>,
    context: DistributedRunnerContext<*, *>,
    numberOfFailedNodes: Int,
    failedNodes: List<Boolean>,
    val edges: List<List<Boolean>>
) : NodeCrashInfo(testCfg, context, numberOfFailedNodes, failedNodes) {
    override fun canSend(a: Int, b: Int): Boolean = edges[a][b]

    private fun createNewEdges(iNode: Int, value: Boolean) : List<List<Boolean>> {
        val newEdges = edges.map { it.toMutableList() }.toMutableList()
        for (i in edges.indices) {
            newEdges[i][iNode] = value
            newEdges[iNode][i] = value
        }
        return newEdges
    }

    override fun crashNode(iNode: Int): NodeCrashInfoSingle? {
        if (numberOfFailedNodes == maxNumberOfFailedNodes ||
            failedNodes[iNode]
        ) return null
        val newFailedNodes = failedNodes.toMutableList()
        newFailedNodes[iNode] = true
        return NodeCrashInfoSingle(
            testCfg,
            context, numberOfFailedNodes + 1, newFailedNodes, createNewEdges(iNode, false)
        )
    }

    override fun recoverNode(iNode: Int): NodeCrashInfoSingle {
        check(failedNodes[iNode])
        val newFailedNodes = failedNodes.toMutableList()
        newFailedNodes[iNode] = false
        return NodeCrashInfoSingle(
            testCfg,
            context, numberOfFailedNodes - 1, newFailedNodes, createNewEdges(iNode, true)
        )
    }

    override fun setNetworkPartition(): NodeCrashInfo? {
        TODO("Not yet implemented")
    }

    override fun recoverNetworkPartition(): NodeCrashInfo {
        TODO("Not yet implemented")
    }
}