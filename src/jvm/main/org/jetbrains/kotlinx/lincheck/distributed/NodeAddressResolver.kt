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

import org.jetbrains.kotlinx.lincheck.distributed.NetworkPartitionMode.SINGLE_EDGE

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
class NodeAddressResolver<Message>(
    testClass: Class<out Node<Message>>,
    val scenarioSize: Int,
    nodeTypes: Map<Class<out Node<Message>>, NodeTypeInfo>,
) {
    val nodeTypeToRange: Map<Class<out Node<Message>>, List<Int>>
    val nodeCount = nodeTypes.values.sumOf { it.nodes }
    private val nodes = mutableListOf<Class<out Node<Message>>>()
    private val crashes = mutableMapOf<Class<out Node<Message>>, CrashInfoForType>()

    init {
        // Nodes with scenario goes first.
        repeat(nodeTypes[testClass]!!.nodes) {
            nodes.add(testClass)
        }
        // Add other node types.
        for ((cls, info) in nodeTypes) {
            if (cls == testClass) continue
            else repeat(info.nodes) { nodes.add(cls) }
        }
        // Store range for each class.
        nodeTypeToRange = nodes.mapIndexed { i, cls -> cls to i }.groupBy({ it.first }, { it.second })
        // Check if configuration is correct (cannot use SINGLE_EDGE with multiple node types).
        if (nodeTypeToRange.size > 1 && nodeTypes.values.any { it.networkPartition == SINGLE_EDGE }) {
            throw IllegalArgumentException("Cannot use this type of network partition with multiple types of nodes. Use 'isNetworkReliable' parameter for message loss instead.")
        }
        // Store crash info for each type.
        nodeTypes.forEach { (cls, info) ->
            crashes[cls] = CrashInfoForType(info.crashType, info.networkPartition, info.maxNumberOfCrashes)
        }
    }

    /**
     * Returns a list of ids for a specified class [cls].
     */
    operator fun get(cls: Class<out Node<Message>>) = nodeTypeToRange[cls] ?: emptyList()

    /**
     * Returns a class for a specified id [iNode].
     */
    operator fun get(iNode: Int) = nodes[iNode]

    /**
     * Returns number of node
     */
    fun size(iNode: Int) = nodeTypeToRange[nodes[iNode]]!!.size

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
    fun crashType(cls: Class<out Node<Message>>) = crashes[cls]!!.crashMode

    /**
     * Returns the partition mode for [cls].
     */
    fun partitionType(cls: Class<out Node<Message>>) = crashes[cls]!!.partitionMode

    /**
     * Returns maximum number of unavailable nodes for [cls].
     */
    fun maxNumberOfCrashes(cls: Class<out Node<Message>>) = crashes[cls]!!.maxNumberOfCrashes

    /**
     * If there are multiple types of nodes in the system.
     */
    val isMultipleType = nodeTypeToRange.size > 1

    /**
     * If the partition type is [NetworkPartitionMode.SINGLE_EDGE] (it is possible if there is only one type of node).
     */
    val singlePartitionType =
        nodeTypes.size == 1 && crashes.all { it.value.partitionMode == SINGLE_EDGE }
}
