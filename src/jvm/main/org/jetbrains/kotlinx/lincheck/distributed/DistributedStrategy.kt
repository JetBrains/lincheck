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

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method

/**
 * Represents the strategy for executing the distributed algorithm.
 */
internal abstract class DistributedStrategy<Message>(
    val testCfg: DistributedCTestConfiguration<Message>,
    protected val testClass: Class<*>,
    scenario: ExecutionScenario,
    protected val validationFunctions: List<Method>,
    protected val stateRepresentationFunction: Method?,
    protected val verifier: Verifier
) : Strategy(scenario) {
    protected lateinit var failureManager: FailureManager<Message>

    fun initialize() {
        failureManager = FailureManager.create(testCfg.addressResolver, this)
    }

    abstract fun reset()

    /**
     * Decides which task from [taskManager] will be executed next.
     */
    abstract fun next(taskManager: TaskManager): Task?

    /**
     * Returns how many times the message with [id][messageId] should be delivered from [sender] to [receiver].
     * If the strategy decides to crash the sender, [CrashError] will be thrown.
     * The result considers possible message loss and duplication, and can initialize the network partition.
     */
    fun crashOrReturnRate(sender: Int, receiver: Int, messageId: Int): Int {
        tryCrash(sender)
        tryAddPartition(sender, receiver, messageId)
        if (!failureManager.canSend(sender, receiver)) return 0
        return getMessageRate(sender, receiver, messageId)
    }

    /**
     * Called when the message from [sender] to [receiver] with id [messageId] was sent.
     * Could cause the crash of the node.
     */
    abstract fun onMessageSent(sender: Int, receiver: Int, messageId: Int)

    /**
     * Called before the database of node [iNode] is accessed.
     * Could cause the crash of the node.
     */
    abstract fun beforeStorageAccess(iNode: Int)

    /**
     * Called when the node [iNode] recovers.
     */
    fun onNodeRecover(iNode: Int) {
        failureManager.recoverNode(iNode)
    }

    /**
     * Chooses the nodes which will be separated from other nodes.
     * [nodes] are the nodes which can be included in the partition.
     * [limit] is the maximum number of nodes which can be included in the partition.
     */
    abstract fun choosePartitionComponent(nodes: List<Int>, limit: Int): List<Int>

    /**
     * Chooses the timeout for node to recover.
     * [taskManager] is used to get the approximate timeout.
     */
    abstract fun getRecoverTimeout(taskManager: TaskManager): Int

    /**
     * Removes the partition between [firstPart] and [secondPart].
     */
    abstract fun recoverPartition(firstPart: List<Int>, secondPart: List<Int>)

    /**
     * Returns if [iNode] is crashed now.
     */
    fun isCrashed(iNode: Int) = failureManager[iNode]

    /**
     * Returns if the [iNode] should be recovered after crash.
     * Makes decision if the crash mode for this type is [CrashMode.FINISH_OR_RECOVER_ON_CRASH].
     */
    abstract fun shouldRecover(iNode: Int): Boolean

    /**
     * Checks if the [test configuration][DistributedCTestConfiguration] allows adding crash,
     * when decides if the crash should be added here and if so throws [CrashError].
     */
    protected abstract fun tryCrash(iNode: Int)

    /**
     * Returns how much times the message from [sender] should be delivered to the receiver.
     * It is guaranteed that it is possible to send message from [sender] to [receiver].
     */
    protected abstract fun getMessageRate(sender: Int, receiver: Int, messageId: Int): Int

    /**
     * Adds the network partition if it is possible according to [test configuration][DistributedCTestConfiguration]
     * and if it should be added according to strategy.
     */
    protected abstract fun tryAddPartition(sender: Int, receiver: Int, messageId: Int): Boolean
}