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

package org.jetbrains.kotlinx.lincheck.test.distributed.totalorder

import org.jetbrains.kotlinx.lincheck.distributed.DistributedVerifier
import org.jetbrains.kotlinx.lincheck.distributed.NodeEnvironment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.event.Event
import org.jetbrains.kotlinx.lincheck.distributed.event.MessageSentEvent
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario

abstract class OrderCheckNode(val env: NodeEnvironment<Message>) :
    Node<Message> {
    val delivered = mutableListOf<BroadcastMessage>()
}

class TotalOrderVerifier : DistributedVerifier {
    override fun verifyResultsAndStates(
        nodes: Array<out Node<*>>,
        scenario: ExecutionScenario,
        results: ExecutionResult,
        events: List<Event>
    ): Boolean {
        val sent = events.filterIsInstance<MessageSentEvent<Message>>().map { it.message }
            .filterIsInstance<BroadcastMessage>()
        val received = nodes.map { (it as OrderCheckNode).delivered }
        val allDelivered =
            sent.all { m -> received.filterIndexed { index, _ -> index != m.sender }.all { it.contains(m) } }
        if (!allDelivered) return false
        for (messages in received) {
            for (i in messages.indices) {
                for (j in i + 1 until messages.size) {
                    if (received.any {
                            val firstIndex = it.lastIndexOf(messages[i])
                            val secondIndex = it.lastIndexOf(messages[j])
                            firstIndex != -1 && secondIndex != -1 && firstIndex >= secondIndex
                        }) return false
                }
            }
        }
        return true
    }
}