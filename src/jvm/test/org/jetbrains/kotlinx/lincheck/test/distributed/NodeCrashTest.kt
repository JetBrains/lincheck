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

package org.jetbrains.kotlinx.lincheck.test.distributed

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.CrashMode.RECOVER_ON_CRASH
import org.jetbrains.kotlinx.lincheck.distributed.NetworkPartitionMode.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test

class Node1(env: NodeEnvironment<Unit>) : Node<Unit> {
    override fun onMessage(message: Unit, sender: Int) {
        TODO("Not yet implemented")
    }

    @Operation
    fun op() {
    }
}

class Node2(env: NodeEnvironment<Unit>) : Node<Unit> {
    override fun onMessage(message: Unit, sender: Int) {
        TODO("Not yet implemented")
    }
}

internal class MockDistributedStrategy() : DistributedStrategy<Unit>(
    DistributedOptions<Unit>().addNodes<Node1>(nodes = 1).createTestConfigurations(Node1::class.java),
    Node1::class.java,
    ExecutionScenario(emptyList(), emptyList(), emptyList()),
    emptyList(),
    EpsilonVerifier(Unit::class.java)
) {
    private var callCount = 0

    override fun beforeStorageAccess(iNode: Int) {
        TODO("Not yet implemented")
    }

    override fun next(taskManager: TaskManager): Task? {
        TODO("Not yet implemented")
    }

    override fun reset() {
        TODO("Not yet implemented")
    }

    override fun choosePartitionComponent(nodes: List<Int>, limit: Int): List<Int> {
        callCount++
        if (callCount % 2 == 1) {
            return nodes.take(limit)
        }
        return if (nodes.isEmpty() || limit == 0) emptyList()
        else listOf(nodes.last())
    }

    override fun run(): LincheckFailure? {
        TODO("Not yet implemented")
    }

    override fun getRecoverTimeout(taskManager: TaskManager): Int {
        TODO("Not yet implemented")
    }

    override fun recoverPartition(firstPart: List<Int>, secondPart: List<Int>) {
        TODO("Not yet implemented")
    }

    override fun shouldRecover(iNode: Int): Boolean {
        TODO("Not yet implemented")
    }

    override fun onMessageSent(sender: Int, receiver: Int) {
        TODO("Not yet implemented")
    }

    override fun tryCrash(iNode: Int) {
        TODO("Not yet implemented")
    }

    override fun getMessageRate(sender: Int, receiver: Int): Int {
        TODO("Not yet implemented")
    }

    override fun tryAddPartition(sender: Int, receiver: Int): Boolean {
        TODO("Not yet implemented")
    }
}

class NodeCrashTest {
    private fun createFailureManager(): FailureManagerComponent<Unit> {
        val typeInfo = mapOf(Node1::class.java to NodeTypeInfo(
            3,
            4,
            RECOVER_ON_CRASH,
            COMPONENTS
        ) { it / 2 },
            Node2::class.java to NodeTypeInfo(1, 3, CrashMode.NO_CRASH, NONE) { 0 })
        return FailureManagerComponent(NodeAddressResolver(Node1::class.java, 2, typeInfo), MockDistributedStrategy())
    }

    private fun createFailureManagerSingleEdge(): FailureManagerSingleEdge<Unit> {
        val typeInfo: Map<Class<out Node<Unit>>, NodeTypeInfo> = mapOf(Node1::class.java to NodeTypeInfo(
            3,
            5,
            RECOVER_ON_CRASH,
            SINGLE_EDGE
        ) { 0 })
        return FailureManagerSingleEdge(NodeAddressResolver(Node1::class.java, 2, typeInfo))
    }

    private fun checkClique(nodes: Iterable<Int>, crashInfo: FailureManager<Unit>) {
        for (i in nodes) {
            for (j in nodes) {
                check(crashInfo.canSend(i, j))
            }
        }
    }

    @Test
    fun testCanSendInitially() {
        val crashInfo = createFailureManager()
        checkClique(0..6, crashInfo)
    }

    @Test
    fun testCanCrashInitially() {
        val crashInfo = createFailureManager()
        for (i in 0..3) {
            check(crashInfo.canCrash(i) && !crashInfo[i])
        }
        for (i in 4..6) {
            check(!crashInfo.canCrash(i) && !crashInfo[i])
        }
    }

    @Test
    fun testCanAddPartitionInitially() {
        val crashInfo = createFailureManager()
        for (i in 0..6) {
            for (j in 0..6) {
                if (i < 4) {
                    check(crashInfo.canAddPartition(i, j))
                } else {
                    check(!crashInfo.canAddPartition(i, j))
                }
            }
        }
    }

    @Test
    fun testCrash() {
        val crashInfo = createFailureManager()
        crashInfo.crashNode(0)
        check(crashInfo[0])
        crashInfo.crashNode(1)
        check(crashInfo[1])
        check(!crashInfo.canCrash(2))
    }

    @Test
    fun testRecover() {
        val crashInfo = createFailureManager()
        crashInfo.crashNode(0)
        crashInfo.crashNode(1)
        crashInfo.recoverNode(0)
        check(crashInfo.canCrash(2) && !crashInfo[0])
    }

    @Test
    fun testPartitionSameType() {
        val crashInfo = createFailureManager()
        crashInfo.partition(0, 1)
        val partition = listOf(0, 2)
        val available = listOf(1, 3, 4, 5, 6)
        for (i in partition) {
            for (j in available) {
                check(!crashInfo.canSend(i, j) && !crashInfo.canSend(j, i))
            }
        }
        checkClique(partition, crashInfo)
        checkClique(available, crashInfo)
    }

    @Test
    fun testPartitionSingleEdge() {
        val failureManager = createFailureManagerSingleEdge()
        assert(!failureManager.canCrash(0))
        failureManager.partition(0, 1)
        assert(!failureManager.canSend(1, 0))
        failureManager.partition(0, 2)
        failureManager.partition(3, 0)
        assert(!failureManager.canAddPartition(4, 0))
        failureManager.partition(4, 1)
        failureManager.partition(2, 4)
        assert(!failureManager.canAddPartition(3, 4))
        assert(failureManager.canSend(4, 3))
        failureManager.removePartition(listOf(0), listOf(3))
        assert(failureManager.canAddPartition(3, 4))
    }
}