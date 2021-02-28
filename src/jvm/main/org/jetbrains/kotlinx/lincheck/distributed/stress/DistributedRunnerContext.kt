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

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.TestNodeExecution
import java.lang.Integer.max


class DistributedRunnerContext<Message, Log>(
    val tesCfg: DistributedCTestConfiguration<Message, Log>,
    val scenario: ExecutionScenario
) {
    val addressResolver = NodeAddressResolver(
        tesCfg.testClass as Class<out Node<Message>>,
        scenario.threads, tesCfg.nodeTypes
    )

    val incomeMessages = Array<Channel<MessageSentEvent<Message>>>(addressResolver.totalNumberOfNodes) {
        Channel { }
    }

    val failureNotifications = Array<Channel<Int>>(addressResolver.totalNumberOfNodes) {
        Channel { }
    }

    val failureInfo = NodeFailureInfo(
        addressResolver.totalNumberOfNodes,
        tesCfg.maxNumberOfFailedNodes(addressResolver.totalNumberOfNodes)
    )

    val events = Array(addressResolver.totalNumberOfNodes) {
        mutableListOf<Event>()
    }

    val messageId = atomic(0)

    lateinit var testNodeExecutions: Array<TestNodeExecution>

    lateinit var testInstances: Array<Node<Message>>

    val vectorClock = Array(addressResolver.totalNumberOfNodes) {
        IntArray(addressResolver.totalNumberOfNodes)
    }

    val currentActors = IntArray(addressResolver.nodesWithScenario)

    fun reset() {
        failureInfo.reset()
        events.forEach { it.clear() }
        vectorClock.forEach { it.fill(0) }
        currentActors.fill(0)
    }

    fun incClock(i: Int) = vectorClock[i][i]++

    fun maxClock(iNode: Int, clock: IntArray): IntArray {
        for (i in vectorClock[iNode].indices) {
            vectorClock[iNode][i] = max(vectorClock[iNode][i], clock[i])
        }
        return vectorClock[iNode].copyOf()
    }
}
