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

private data class CrashInfoForType(
    val crashMode: CrashMode,
    val partitionMode: NetworkPartitionMode,
    val maxNumberOfCrashes: Int
)

/**
 * Stores information about classes included in the scenario execution.
 * Maps a node id to the corresponding class and the class to a range of corresponding ids.
 * Keeps information of crash types for each class and maximum number of crashes.
 */
class NodeAddressResolver<Message, DB>(
    testClass: Class<out Node<Message, DB>>,
    val nodesWithScenario: Int,
    nodeTypes: Map<Class<out Node<Message, DB>>, NodeTypeInfo>,
) {
    val nodeTypeToRange: Map<Class<out Node<Message, DB>>, List<Int>>
    val nodeCount =
        if (testClass in nodeTypes) nodeTypes.values.sumOf { it.numberOfInstances } else nodeTypes.values.sumOf { it.numberOfInstances } + nodesWithScenario
    private val nodes = mutableListOf<Class<out Node<Message, DB>>>()
    private val crashes = mutableMapOf<Class<out Node<Message, DB>>, CrashInfoForType>()

    init {
        repeat(nodesWithScenario) { nodes.add(testClass) }
        if (nodeTypes.containsKey(testClass)) {
            repeat(nodeTypes[testClass]!!.numberOfInstances - nodesWithScenario) {
                nodes.add(testClass)
            }
        }
        for ((cls, info) in nodeTypes) {
            if (cls == testClass) continue
            else repeat(info.numberOfInstances) { nodes.add(cls) }
        }
        nodeTypeToRange = nodes.mapIndexed { i, cls -> cls to i }.groupBy({ it.first }, { it.second })
        if (nodeTypeToRange.size > 1 && nodeTypes.values.any { it.networkPartition == NetworkPartitionMode.SINGLE_EDGE }) {
            throw IllegalArgumentException("Cannot use this type of network partition with multiple types of nodes. Use 'isNetworkReliable' parameter for message loss instead.")
        }
        nodeTypes.forEach { (cls, info) ->
            crashes[cls] = CrashInfoForType(info.crashType, info.networkPartition, info.maxNumberOfCrashes)
        }
        if (testClass !in crashes) {
            crashes[testClass] = CrashInfoForType(CrashMode.NO_CRASHES, NetworkPartitionMode.NONE, 0)
        }
    }

    /**
     * Returns a list of ids for a specified class [cls].
     */
    operator fun get(cls: Class<out Node<Message, DB>>) = nodeTypeToRange[cls]

    /**
     * Returns a class for a specified id [iNode].
     */
    operator fun get(iNode: Int) = nodes[iNode]

    /**
     * Returns the crash mode for [iNode].
     */
    fun crashTypeForNode(iNode: Int) = crashes[get(iNode)]!!.crashMode

    /**
     * Returns the partition mode for [iNode].
     */
    fun partitionTypeForNode(iNode: Int) = crashes[get(iNode)]!!.partitionMode

    /**
     * Returns the crash mode for [cls].
     */
    fun crashType(cls: Class<out Node<Message, DB>>) = crashes[cls]!!.crashMode

    /**
     * Returns the partition mode for [cls].
     */
    fun partitionType(cls: Class<out Node<Message, DB>>) = crashes[cls]!!.partitionMode

    /**
     * Returns maximum number of unavailable nodes for [cls].
     */
    fun maxNumberOfCrashes(cls: Class<out Node<Message, DB>>) = crashes[cls]!!.maxNumberOfCrashes

    /**
     * If there are multiple types of nodes in the system.
     */
    val isMultipleType = nodeTypeToRange.size > 1

    /**
     * If the partition type is [NetworkPartitionMode.SINGLE_EDGE] (it is possible if there is only one type of node).
     */
    val singlePartitionType =
        nodeTypes.size == 1 && crashes.all { it.value.partitionMode == NetworkPartitionMode.SINGLE_EDGE }
}
