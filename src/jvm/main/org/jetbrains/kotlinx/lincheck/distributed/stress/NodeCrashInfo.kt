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
import org.jetbrains.kotlinx.lincheck.distributed.Node
import java.util.concurrent.PriorityBlockingQueue

class NodeCrashInfo(
    private val testCfg: DistributedCTestConfiguration<*, *>,
    private val context: DistributedRunnerContext<*, *>,
    private val numberOfFailedNodes: Int,
    private val partitions: List<Set<Int>>,
    private val failedNodes: List<Boolean>,
) {
    companion object {
        fun initialInstance(
            testCfg: DistributedCTestConfiguration<*, *>,
            context: DistributedRunnerContext<*, *>
        ): NodeCrashInfo {
            val numberOfNodes = context.addressResolver.totalNumberOfNodes
            val failedNodes = List(numberOfNodes) { false }
            val partitions = listOf(emptySet(), (0 until numberOfNodes).toSet())
            return NodeCrashInfo(testCfg, context, 0, partitions, failedNodes)
        }
    }

    val maxNumberOfFailedNodes = testCfg.maxNumberOfFailedNodes(context.addressResolver.totalNumberOfNodes)

    operator fun get(a: Int, b: Int): Boolean {
        return !failedNodes[a] && !failedNodes[b] &&
                (partitions[0].contains(a) && partitions[0].contains(b) ||
                        partitions[1].contains(a) && partitions[1].contains(b))
    }

    operator fun get(a: Int) = failedNodes[a]

    fun crashNode(iNode: Int): NodeCrashInfo? {
        if (numberOfFailedNodes == maxNumberOfFailedNodes ||
            failedNodes[iNode]
        ) return null
        val newFailedNodes = failedNodes.toMutableList()
        newFailedNodes[iNode] = true
        return NodeCrashInfo(
            testCfg,
            context, numberOfFailedNodes + 1, partitions, newFailedNodes
        )
    }

    fun recoverNode(iNode: Int): NodeCrashInfo {
        check(failedNodes[iNode])
        val newFailedNodes = failedNodes.toMutableList()
        newFailedNodes[iNode] = false
        return NodeCrashInfo(
            testCfg,
            context, numberOfFailedNodes - 1, partitions, newFailedNodes
        )
    }
}