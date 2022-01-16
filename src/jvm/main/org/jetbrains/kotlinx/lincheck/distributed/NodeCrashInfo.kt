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

internal abstract class CrashInfo<Message, DB>(
    protected val addressResolver: NodeAddressResolver<Message, DB>
) {
    companion object {
        fun <Message, DB> createCrashInfo(
            addressResolver: NodeAddressResolver<Message, DB>,
            strategy: DistributedStrategy<Message, DB>
        ): CrashInfo<Message, DB> =
            CrashInfoHalves(addressResolver, strategy)
    }

    protected val failedNode = Array(addressResolver.nodeCount) {
        false
    }

    abstract fun canSend(from: Int, to: Int): Boolean

    operator fun get(iNode: Int) = failedNode[iNode]

    abstract fun crashNode(iNode: Int)

    abstract fun canAddPartition(firstNode: Int, secondNode: Int): Boolean

    abstract fun canCrash(iNode: Int): Boolean

    abstract fun recoverNode(iNode: Int)

    abstract fun addPartition(firstNode: Int, secondNode: Int): Pair<List<Int>, List<Int>>

    abstract fun removePartition(firstNode: Int, secondNode: Int)

    abstract fun reset()
}

internal class CrashInfoHalves<Message, DB>(
    addressResolver: NodeAddressResolver<Message, DB>,
    private val strategy: DistributedStrategy<Message, DB>
) :
    CrashInfo<Message, DB>(addressResolver) {
    private val partitions = mutableMapOf<Class<out Node<Message, DB>>, Pair<MutableSet<Int>, MutableSet<Int>>>()
    private val unavailableNodeCount = mutableMapOf<Class<out Node<Message, DB>>, Int>()

    init {
        reset()
    }

    override fun canSend(from: Int, to: Int): Boolean {
        if (failedNode[from] || failedNode[to]) {
            return false
        }
        val cls1 = addressResolver[from]
        val cls2 = addressResolver[to]
        if (cls1 != cls2) {
            return partitions[cls1]!!.second.contains(from)
                    && partitions[cls2]!!.second.contains(to)
        }
        return partitions[cls1]!!.first.containsAll(listOf(from, to))
                || partitions[cls1]!!.second.containsAll(listOf(from, to))
    }

    private fun incrementCrashedNodes(iNode: Int) {
        val cls = addressResolver[iNode]
        if (failedNode[iNode] || partitions[cls]!!.first.contains(iNode)) return
        unavailableNodeCount[cls] = unavailableNodeCount[cls]!! + 1
    }

    private fun decrementCrashedNodes(iNode: Int) {
        val cls = addressResolver[iNode]
        if (failedNode[iNode] && partitions[cls]!!.first.contains(iNode)) return
        unavailableNodeCount[cls] = unavailableNodeCount[cls]!! - 1
    }

    override fun crashNode(iNode: Int) {
        check(!failedNode[iNode])
        incrementCrashedNodes(iNode)
        failedNode[iNode] = true
    }

    override fun canCrash(iNode: Int): Boolean {
        if (failedNode[iNode]) return false
        val cls = addressResolver[iNode]
        return addressResolver.crashType(cls) != CrashMode.NO_CRASHES
                && unavailableNodeCount[cls]!! < addressResolver.maxNumberOfCrashes(cls)
    }

    override fun recoverNode(iNode: Int) {
        check(failedNode[iNode])
        decrementCrashedNodes(iNode)
        failedNode[iNode] = false
    }

    override fun canAddPartition(firstNode: Int, secondNode: Int): Boolean {
        val cls = addressResolver[firstNode]
        if (addressResolver.partitionType(cls) == NetworkPartitionMode.NONE) {
            return false
        }
        return unavailableNodeCount[cls]!! < addressResolver.maxNumberOfCrashes(cls)
                && partitions[cls]!!.second.contains(firstNode)
    }

    override fun addPartition(firstNode: Int, secondNode: Int): Pair<List<Int>, List<Int>> {
        addNodeToPartition(firstNode)
        removeNodeFromPartition(secondNode)
        val cls = addressResolver[firstNode]
        val nodes = addressResolver.nodeTypeToRange[cls]!!.filter { it != firstNode && it != secondNode }
        val limit = addressResolver.maxNumberOfCrashes(cls) - unavailableNodeCount[cls]!!
        val nodesForPartition = strategy.choosePartitionComponent(nodes, limit)
        for (node in nodes) {
            if (node in nodesForPartition) {
                addNodeToPartition(node)
            } else {
                removeNodeFromPartition(node)
            }
        }
        val firstPart = nodesForPartition + firstNode
        return firstPart to (0 until addressResolver.nodeCount).filter { it !in firstPart }
    }

    private fun addNodeToPartition(iNode: Int) {
        val cls = addressResolver[iNode]
        if (partitions[cls]!!.first.contains(iNode)) return
        incrementCrashedNodes(iNode)
        partitions[cls]!!.second.remove(iNode)
        partitions[cls]!!.first.add(iNode)
    }

    private fun removeNodeFromPartition(iNode: Int) {
        val cls = addressResolver[iNode]
        if (partitions[cls]!!.second.contains(iNode)) return
        decrementCrashedNodes(iNode)
        partitions[cls]!!.first.remove(iNode)
        partitions[cls]!!.second.add(iNode)
    }

    override fun removePartition(firstNode: Int, secondNode: Int) {
        removeNodeFromPartition(secondNode)
        for (node in partitions[addressResolver[firstNode]]!!.first) {
            removeNodeFromPartition(node)
        }
    }

    override fun reset() {
        failedNode.fill(false)
        addressResolver.nodeTypeToRange.forEach { (cls, range) ->
            partitions[cls] = mutableSetOf<Int>() to range.toMutableSet()
            unavailableNodeCount[cls] = 0
        }
    }
}

internal class CrashInfoSingle<Message, DB>(
    addressResolver: NodeAddressResolver<Message, DB>
) : CrashInfo<Message, DB>(addressResolver) {
    private val nodeCount = addressResolver.nodeCount
    private val connections = Array(nodeCount) {
        (0 until nodeCount).toMutableSet()
    }

    private fun findMaxComponentSize(): Int {
        val visited = Array(nodeCount) { false }
        val componentSizes = mutableListOf<Int>()
        for (node in 0 until nodeCount) {
            if (visited[node]) continue
            componentSizes.add(dfs(node, visited))
        }
        return componentSizes.maxOrNull()!!
    }

    private fun dfs(node: Int, visited: Array<Boolean>): Int {
        if (visited[node]) return 0
        visited[node] = true
        return connections[node].sumOf { dfs(it, visited) } + 1
    }

    override fun canSend(from: Int, to: Int): Boolean {
        return connections[from].contains(to)
    }

    override fun crashNode(iNode: Int) {
        check(!failedNode[iNode])
        failedNode[iNode] = true
        for (node in 0 until nodeCount) {
            connections[node].remove(iNode)
        }
        connections[iNode].clear()
    }

    override fun canAddPartition(firstNode: Int, secondNode: Int): Boolean {
        if (!connections[firstNode].contains(secondNode) || firstNode == secondNode) return false
        addPartition(firstNode, secondNode)
        val size = findMaxComponentSize()
        val res = (size >= nodeCount - addressResolver.maxNumberOfCrashes(addressResolver[firstNode]))
        removePartition(firstNode, secondNode)
        return res
    }

    override fun canCrash(iNode: Int): Boolean {
        crashNode(iNode)
        val size = findMaxComponentSize()
        val res = (size >= nodeCount - addressResolver.maxNumberOfCrashes(addressResolver[iNode]))
        recoverNode(iNode)
        return res
    }

    override fun recoverNode(iNode: Int) {
        check(failedNode[iNode])
        failedNode[iNode] = false
        for (node in 0 until nodeCount) {
            connections[node].add(iNode)
            connections[iNode].add(node)
        }
    }

    override fun addPartition(firstNode: Int, secondNode: Int): Pair<List<Int>, List<Int>> {
        connections[firstNode].remove(secondNode)
        connections[secondNode].remove(firstNode)
        return listOf(firstNode) to listOf(secondNode)
    }

    override fun removePartition(firstNode: Int, secondNode: Int) {
        connections[firstNode].add(secondNode)
        connections[secondNode].add(firstNode)
    }

    override fun reset() {
        failedNode.fill(false)
        for (i in 0 until nodeCount) {
            connections[i] = (0 until nodeCount).toMutableSet()
        }
    }
}
