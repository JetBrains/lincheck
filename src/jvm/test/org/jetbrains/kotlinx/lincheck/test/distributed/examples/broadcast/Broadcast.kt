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

package org.jetbrains.kotlinx.lincheck.test.distributed.examples.broadcast

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.distributed.CrashMode.FINISH_ON_CRASH
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.DistributedVerifier
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.event.Event
import org.jetbrains.kotlinx.lincheck.distributed.event.NodeCrashEvent
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.junit.Test
import java.util.*

data class Message(val body: String, val id: Int, val from: Int)

/**
 * Abstract class for node participating in broadcast which validates the results.
 */
abstract class AbstractPeer(protected val env: Environment<Message>) :
    Node<Message> {
    val delivered = Array(env.nodes) {
        mutableListOf<String>()
    }
}

class Peer(env: Environment<Message>) : AbstractPeer(env) {
    private val messageCount = Array<MutableMap<Int, Int>>(env.nodes) { mutableMapOf() }
    private var messageId = 0
    private val undeliveredMessages = Array<PriorityQueue<Message>>(env.nodes) {
        PriorityQueue { x, y -> x.id - y.id }
    }
    private val lastDeliveredId = Array(env.nodes) { -1 }

    override fun stateRepresentation(): String {
        return "{Undelivered=${undeliveredMessages.map { it.toList() }}, " +
                "delivered=${delivered.map { it.size to it }}, messageCount = ${messageCount.toList()}}"
    }

    private fun deliver(sender: Int) {
        while (undeliveredMessages[sender].isNotEmpty()) {
            val lastMessage = undeliveredMessages[sender].peek()
            if (lastMessage.id != lastDeliveredId[sender] + 1
                || messageCount[sender][lastMessage.id]!! < (env.nodes + 1) / 2
            ) {
                return
            }
            undeliveredMessages[sender].remove()
            lastDeliveredId[sender]++
            env.recordInternalEvent("Try to deliver message $lastMessage, ${delivered[lastMessage.id].size}")
            delivered[lastMessage.from].add(lastMessage.body)
            assert(delivered[lastMessage.from].last() == lastMessage.body)
            env.recordInternalEvent("${delivered[lastMessage.from].last()}, ${delivered[lastMessage.from]}, ${delivered[lastMessage.from].size}")
        }
    }

    override fun onMessage(message: Message, sender: Int) {
        val msgId = message.id
        val from = message.from
        if (!messageCount[from].contains(msgId)) {
            messageCount[from][msgId] = 2
            undeliveredMessages[from].add(message)
            env.broadcast(message)
        } else {
            messageCount[from][msgId] = messageCount[from][msgId]!! + 1
        }
        deliver(from)
    }

    @Operation(cancellableOnSuspension = false)
    fun send(msg: String) {
        val message = Message(body = msg, id = messageId++, from = env.id)
        env.recordInternalEvent(messageId)
        env.broadcast(message, false)
    }
}

class BroadcastVerifier : DistributedVerifier {
    override fun verifyResultsAndStates(
        nodes: Array<out Node<*>>,
        scenario: ExecutionScenario,
        results: ExecutionResult,
        events: List<Event>
    ): Boolean {
        val sentMessages: Array<List<String>> = Array(nodes.size) { emptyList() }
        scenario.parallelExecution.forEachIndexed { index, operations ->
            sentMessages[index] = operations.map { a -> a.arguments[0] as String }
        }
        val deliveredMessages = nodes.map { (it as AbstractPeer).delivered }
        val crashedNodes = events.filterIsInstance<NodeCrashEvent>().map { it.iNode }.toSet()
        val correctNodes = nodes.indices.filter { it !in crashedNodes }.toSet()
        for (sender in nodes.indices) {
            for (receiver in nodes.indices) {
                val delivered = deliveredMessages[receiver][sender]
                val size = delivered.size
                // If message m from process s was delivered, it was sent by process s before.
                // If some process sent m1 before m2, every process which delivered m2 delivered m1.
                if (delivered != sentMessages[sender].take(size)) return false
                // If the message was delivered to one process, it was delivered to all correct processes.
                if (correctNodes.any { deliveredMessages[it][sender].take(size) != delivered }) return false
            }
        }
        // If the correct process sent message m, it should deliver m.
        return correctNodes.all { deliveredMessages[it][it] == sentMessages[it] }
    }
}

class BroadcastTest {
    private fun commonOptions() = DistributedOptions<Message>()
        .actorsPerThread(3)
        .invocationsPerIteration(100_000)
        .iterations(1) // we always have the same scenario here
        .verifier(BroadcastVerifier::class.java)

    @Test
    fun `correct algorithm`() = commonOptions()
        .addNodes<Peer>(
            minNodes = 2,
            nodes = 4,
            crashMode = FINISH_ON_CRASH,
            maxUnavailableNodes = { it / 2 }
        )
        .storeLogsForFailedScenario("broadcast.txt")
        .check(Peer::class.java)

    @Test
    fun `correct algorithm with too much unavailable nodes`() {
        val failure = commonOptions()
            .addNodes<Peer>(
                nodes = 5,
                crashMode = FINISH_ON_CRASH,
                maxUnavailableNodes = { it / 2 + 1 }
            )
            .minimizeFailedScenario(false)
            .checkImpl(Peer::class.java)
        assert(failure is IncorrectResultsFailure)
    }

    @Test
    fun `incorrect algorithm without crashes`() = commonOptions()
        .addNodes<PeerIncorrect>(nodes = 3)
        .check(PeerIncorrect::class.java)

    @Test
    fun `incorrect algorithm`() {
        val failure = commonOptions()
            .addNodes<PeerIncorrect>(
                nodes = 4,
                crashMode = FINISH_ON_CRASH,
                maxUnavailableNodes = { it / 2 }
            )
            .minimizeFailedScenario(false)
            .checkImpl(PeerIncorrect::class.java)
        assert(failure is IncorrectResultsFailure)
    }
}