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
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.createDistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.event.Event
import org.jetbrains.kotlinx.lincheck.distributed.event.MessageSentEvent
import org.jetbrains.kotlinx.lincheck.distributed.event.NodeCrashEvent
import org.jetbrains.kotlinx.lincheck.strategy.ValidationFailure
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test
import java.util.*

data class Message(val body: String, val id: Int, val from: Int)

/**
 * Returns the list of node ids which didn't crash during the execution.
 */
fun List<Event>.correctProcesses() =
    groupBy { it.iNode }.filter { it.value.none { p -> p is NodeCrashEvent } }
        .map { it.key }

/**
 * Returns the list of messages sent by the node [iNode].
 */
fun <Message> List<Event>.sentMessages(iNode: Int) =
    filter { it.iNode == iNode }.filterIsInstance<MessageSentEvent<Message>>().map { it.message }

/**
 * Returns if the messages in the list are distinct.
 */
fun <Message> List<Message>.isDistinct(): Boolean = distinctBy { System.identityHashCode(it) } == this

/**
 * Returns if the node [iNode] is correct (didn't crash during the execution).
 */
fun List<Event>.isCorrect(iNode: Int): Boolean = none { it.iNode == iNode && it is NodeCrashEvent }

/**
 * Abstract class for node participating in broadcast which validates the results.
 */
abstract class AbstractPeer(protected val env: Environment<Message, MutableList<Message>>) :
    Node<Message, MutableList<Message>> {
    override fun validate(events: List<Event>, databases: List<MutableList<Message>>) {
        check(databases[env.nodeId].isDistinct()) { "Process ${env.nodeId} contains repeated messages" }
        // If message m from process s was delivered, it was sent by process s before.
        databases[env.nodeId].forEach { m ->
            check(events.sentMessages<Message>(m.from).contains(m))
        }
        // If the correct process sent message m, it should deliver m.
        if (events.isCorrect(env.nodeId)) {
            events.sentMessages<Message>(env.nodeId).forEach { m -> check(m in databases[env.nodeId]) }
        }
        // If the message was delivered to one process, it was delivered to all correct processes.
        databases[env.nodeId].forEach { m ->
            events.correctProcesses().forEach { check(databases[it!!].contains(m)) }
        }
        // If some process sent m1 before m2, every process which delivered m2 delivered m1.
        //For each store the order in which messages were sent
        val localMessagesOrder = Array(env.numberOfNodes) { i ->
            databases[env.nodeId].filter { it.from == i }
                .map { m ->
                    events.sentMessages<Message>(i).filter { it.from == i }.distinctBy { it.id }.indexOf(m)
                }
        }
        // Check if message order contains all numbers from 0 to
        localMessagesOrder.filter { it.isNotEmpty() }.forEach {
            check(it == it.indices.toList())
        }
    }
}

class Peer(env: Environment<Message, MutableList<Message>>) : AbstractPeer(env) {
    private val receivedMessages = Array<MutableMap<Int, Int>>(env.numberOfNodes) { mutableMapOf() }
    private var messageId = 0
    private val undeliveredMessages = Array<PriorityQueue<Message>>(env.numberOfNodes) {
        PriorityQueue { x, y -> x.id - y.id }
    }
    private val lastDeliveredId = Array(env.numberOfNodes) { -1 }

    override fun stateRepresentation(): String {
        return "Received messages=${receivedMessages.toList()}, undelivered ${undeliveredMessages.toList()}, " +
                "logs=${env.database}"
    }

    private fun deliver(sender: Int) {
        while (undeliveredMessages[sender].isNotEmpty()) {
            val lastMessage = undeliveredMessages[sender].peek()
            if (lastMessage.id != lastDeliveredId[sender] + 1 || receivedMessages[sender][lastMessage.id]!! < (env.numberOfNodes + 1) / 2) {
                return
            }
            undeliveredMessages[sender].remove()
            lastDeliveredId[sender]++
            env.database.add(lastMessage)
        }
    }

    override fun onMessage(message: Message, sender: Int) {
        val msgId = message.id
        val from = message.from
        if (!receivedMessages[from].contains(msgId)) {
            receivedMessages[from][msgId] = 2
            undeliveredMessages[from].add(message)
            env.broadcast(message)
        } else {
            receivedMessages[from][msgId] = receivedMessages[from][msgId]!! + 1
        }
        deliver(from)
    }

    @Operation(cancellableOnSuspension = false)
    fun send(msg: String) {
        val message = Message(body = msg, id = messageId++, from = env.nodeId)
        env.broadcast(message, false)
    }
}

class BroadcastTest {
    private fun commonOptions() = createDistributedOptions<Message, MutableList<Message>> { mutableListOf() }
        .requireStateEquivalenceImplCheck(false)
        .actorsPerThread(3)
        .invocationsPerIteration(30_000)
        .iterations(1) // we always have the same scenario here
        .verifier(EpsilonVerifier::class.java)

    @Test
    fun `correct algorithm`() = commonOptions()
        .addNodes<Peer>(
            minNodes = 2,
            nodes = 4,
            crashMode = FINISH_ON_CRASH,
            maxUnavailableNodes = { it / 2 }
        )
        .check(Peer::class.java)

    @Test
    fun `correct algorithm with too much unavailable nodes`() {
        val failure = commonOptions()
            .addNodes<Peer>(
                nodes = 5,
                crashMode = FINISH_ON_CRASH,
                maxUnavailableNodes = { it / 2 + 1 }
            )
            .invocationsPerIteration(50_000)
            .minimizeFailedScenario(false)
            .checkImpl(Peer::class.java)
        assert(failure is ValidationFailure)
    }

    @Test
    fun `incorrect algorithm without crashes`() = commonOptions()
        .addNodes<PeerIncorrect>(nodes = 3)
        .invocationsPerIteration(100_000)
        .check(PeerIncorrect::class.java)

    @Test
    fun `incorrect algorithm`()  {
        val failure = commonOptions()
            .addNodes<PeerIncorrect>(
                nodes = 4,
                crashMode = FINISH_ON_CRASH,
                maxUnavailableNodes = { it / 2 }
            )
            .minimizeFailedScenario(false)
            .checkImpl(PeerIncorrect::class.java)
        assert(failure is ValidationFailure)
    }
}