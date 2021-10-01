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

package org.jetbrains.kotlinx.lincheck.distributed.modelchecking
/*
sealed class Switch {
    abstract val after: Int
    abstract val taskId: Int
}

data class Inversion(override val after: Int, override val taskId: Int) : Switch()
data class NodeCrash(override val after: Int, override val taskId: Int, val msgId: Int, val iNode: Int) : Switch()
data class NodeRecover(override val after: Int, override val taskId: Int, val iNode: Int) : Switch()
data class MessageLose(override val after: Int, override val taskId: Int, val msgId: Int) : Switch()
data class MessageDuplication(override val after: Int, override val taskId: Int, val msgId: Int) : Switch()

class InterleavingTreeNode() {
    val unexploredSwitchPoints = mutableSetOf<Switch>()
    val children = mutableMapOf<Switch, InterleavingTreeNode>()
    var isFinished = false
    var isFullyExplored = false
    var fractionUnexplored = 1.0
    var minDistance: Long = 1
    val treeNodeId = context.treeNodeId++

    fun addSwitchPoint(switch: Switch) {
        if (isFinished) return
        unexploredSwitchPoints.add(switch)
    }

    operator fun get(switch: Switch) = children[switch]

    private fun createNode(switch: Switch) {
        unexploredSwitchPoints.remove(switch)
        children[switch] = InterleavingTreeNode(context)
    }

    fun chooseNextInterleaving(builder: InterleavingTreeBuilder): Interleaving? {
        if (unexploredSwitchPoints.isNotEmpty()) {
            val switch = unexploredSwitchPoints.random(context.generatingRandom)
            builder.addSwitch(switch)
            createNode(switch)
            return builder.build()
        }
        if (children.isEmpty()) return builder.build()
        val possibleMoves = children.filter { it.value.minDistance == minDistance - 1 }
        val total = possibleMoves.values.sumByDouble { it.fractionUnexplored }
        val random = context.generatingRandom.nextDouble() * total
        var sumWeight = 0.0
        possibleMoves.forEach { choice ->
            sumWeight += choice.value.fractionUnexplored
            if (sumWeight >= random) {
                builder.addSwitch(
                    choice.key
                )
                return choice.value.chooseNextInterleaving(builder)
            }
        }
        val choice = possibleMoves.entries.last { !it.value.isFullyExplored }
        builder.addSwitch(choice.key)
        return choice.value.chooseNextInterleaving(builder)
    }

    fun updateStats() {
        if (unexploredSwitchPoints.isEmpty() && children.isEmpty()) {
            isFullyExplored = true
            fractionUnexplored = 0.0
            minDistance = Int.MAX_VALUE.toLong()
            return
        }
        isFinished = true
        val total = children.values.fold(0.0) { acc, choice ->
            acc + choice.fractionUnexplored
        } + unexploredSwitchPoints.size
        fractionUnexplored = total / (children.size + unexploredSwitchPoints.size)
        isFullyExplored = unexploredSwitchPoints.isEmpty() && children.all { it.value.isFullyExplored }
        minDistance = if (unexploredSwitchPoints.isNotEmpty()) 1 else children.values.minOf { it.minDistance + 1 }
    }
}

class Interleaving(val path: List<Switch>) {
    var currentSwitch = -1

    fun getSwitch() = path[currentSwitch]

    fun nextSwitch() = path.getOrNull(currentSwitch + 1)
}

class InterleavingTreeBuilder(val switchLimit: Int, val crashInfo: NodeCrashInfo) {
    val path = mutableListOf<Switch>()

    fun addSwitch(switch: Switch) {
        check(path.size < switchLimit) {
            "Path size ${path.size}, switch limit ${switchLimit}"
        }
        path.add(switch)
    }

    fun build() = Interleaving(path)
    fun remainedSwitches() = switchLimit - path.size
}*/