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


class NodeAddressResolver<Message>(
    testClass: Class<out Node<Message>>,
    val nodesWithScenario: Int,
    additionalClasses: Map<Class<out Node<Message>>, Int>
) {
    private val nodeTypeToRange: Map<Class<out Node<Message>>, List<Int>>
    val totalNumberOfNodes = nodesWithScenario + additionalClasses.values.sum()
    private val nodes = mutableListOf<Class<out Node<Message>>>()

    init {
        repeat(nodesWithScenario) {
            nodes.add(testClass)
        }
        for ((cls, num) in additionalClasses) {
            repeat(num) {
                nodes.add(cls)
            }
        }
        nodeTypeToRange = nodes.mapIndexed { i, cls -> cls to i }.groupBy({ it.first }, { it.second })
    }

    operator fun get(cls: Class<out Node<Message>>) = nodeTypeToRange[cls]
    operator fun get(iNode: Int) = nodes[iNode]
}
