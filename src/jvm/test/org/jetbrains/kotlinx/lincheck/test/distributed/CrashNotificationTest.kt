/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.event.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.junit.Test

class CrashingNode(private val env: Environment<Int>) : Node<Int> {
    private val receivedMessages = mutableSetOf<Int>()
    override fun onMessage(message: Int, sender: Int) {
        if (receivedMessages.add(message)) {
            env.send(-message, sender)
        }
    }

    @Operation
    fun operation(value: Int) {
        env.broadcast(value)
    }
}

class CrashNotificationVerifier : DistributedVerifier {
    override fun verifyResultsAndStates(
        nodes: Array<out Node<*>>,
        scenario: ExecutionScenario,
        results: ExecutionResult,
        events: List<Event>
    ): Boolean {
        data class ExpectedNotification(val from: Int, val to: Int)

        val expectedNotifications = mutableListOf<ExpectedNotification>()
        val crashes = mutableListOf<Int>()
        for (e in events) {
            when (e) {
                is NodeCrashEvent -> {
                    expectedNotifications.removeIf { it.to == e.iNode }
                    crashes.add(e.iNode)
                    nodes.indices.filter { it !in crashes }
                        .forEach { expectedNotifications.add(ExpectedNotification(e.iNode, it)) }
                }
                is CrashNotificationEvent -> {
                    val index = expectedNotifications.indexOfFirst { it.from == e.crashedNode && e.iNode == it.to }
                    if (index == -1) return false
                    expectedNotifications.removeAt(index)
                }
                is NodeRecoveryEvent -> {
                    crashes.remove(e.iNode)
                }
                is NetworkPartitionEvent -> {
                    for (i in e.firstPart) {
                        for (j in e.secondPart) {
                            if (j !in crashes) expectedNotifications.add(ExpectedNotification(i, j))
                            if (i !in crashes) expectedNotifications.add(ExpectedNotification(j, i))
                        }
                    }
                }
                else -> continue
            }
        }
        return expectedNotifications.isEmpty()
    }

}

class CrashNotificationTest {
    @Test
    fun test() = DistributedOptions<Int>()
        .addNodes<CrashingNode>(
            nodes = 4,
            minNodes = 2,
            crashMode = CrashMode.RECOVER_ON_CRASH,
            NetworkPartitionMode.COMPONENTS
        )
        .verifier(CrashNotificationVerifier::class.java)
        .iterations(1)
        .invocationsPerIteration(500_000)
        .check(CrashingNode::class.java)
}
