/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test.distributed.broadcast

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test
import java.util.*

data class Message(val body: String, val id: Int, val from: Int)


fun <Message, Log> Environment<Message, Log>.correctProcesses() =
    events().mapIndexed { index, list -> index to list }.filter { !it.second.any { p -> p is ProcessFailureEvent } }
        .map { it.first }

fun <Message, Log> Environment<Message, Log>.sentMessages(processId: Int = nodeId) =
    events()[processId].filterIsInstance<MessageSentEvent<Message>>()

fun <Message, Log> Environment<Message, Log>.receivedMessages(processId: Int = nodeId) =
    events()[processId].filterIsInstance<MessageReceivedEvent<Message>>().filter { it.receiver == processId }

fun <Message> List<Message>.isDistinct(): Boolean = distinctBy { System.identityHashCode(it) } == this
fun <Message, Log> Environment<Message, Log>.isCorrect() = correctProcesses().contains(nodeId)

/**
 *
 */
class Peer(private val env: Environment<Message, Message>) : Node<Message> {
    private val receivedMessages = Array<HashMap<Int, Int>>(env.numberOfNodes) { HashMap() }
    private var messageId = 0
    private val undeliveredMessages = Array<PriorityQueue<Message>>(env.numberOfNodes) {
        PriorityQueue { x, y -> x.id - y.id }
    }
    private val lastDeliveredId = Array(env.numberOfNodes) { -1 }

    private fun deliver(sender: Int) {
        //println("[${env.nodeId}]: In deliver ${undeliveredMessages[sender]}")
        while (undeliveredMessages[sender].isNotEmpty()) {
            val lastMessage = undeliveredMessages[sender].peek()
            if (lastMessage.id != lastDeliveredId[sender] + 1 || receivedMessages[sender][lastMessage.id]!! < (env.numberOfNodes + 1) / 2) {
                //println("[${env.nodeId}]: Last delivered id ${lastDeliveredId[sender]}, $lastMessage, ${receivedMessages[sender][lastMessage.id]!!}")
                return
            }
            undeliveredMessages[sender].remove()
            lastDeliveredId[sender]++
            env.log.add(lastMessage)
        }
    }

    @Validate
    fun validateResults() {
        // All messages were delivered at most once.
        check(env.log.isDistinct()) { "Process ${env.nodeId} contains repeated messages" }
        // If message m from process s was delivered, it was sent by process s before.
        env.log.forEach { m -> check(env.sentMessages(m.from).map { it.message }.contains(m)) }
        // If the correct process sent message m, it should deliver m.
        if (env.isCorrect()) {
            env.log.forEach { m -> check(env.sentMessages().map { it.message }.contains(m)) }
        }
        // If the message was delivered to one process, it was delivered to all correct processes.
        //println(env.correctProcesses())
        env.log.forEach { m ->
            env.correctProcesses().forEach { check(env.log.contains(m)) { env.log } }
        }
        // If some process sent m1 before m2, every process which delivered m2 delivered m1.
        val localMessagesOrder = Array(env.numberOfNodes) { i ->
            env.log.filter { it.from == i }.map { m -> env.sentMessages(i).map { it.message }.indexOf(m) }
        }
        localMessagesOrder.forEach { check(it.sorted() == it) }
    }

    override suspend fun onMessage(message: Message, sender: Int) {
        val msgId = message.id
        val from = message.from
        if (!receivedMessages[from].contains(msgId)) {
            receivedMessages[from][msgId] = 1
            undeliveredMessages[from].add(message)
            env.broadcast(message)
        } else {
            //println("[${env.nodeId}]: $message received ${receivedMessages[from][msgId]} times")
            receivedMessages[from][msgId] = receivedMessages[from][msgId]!! + 1
        }
        deliver(from)
    }

    //@Operation(handleExceptionsAsResult = [NodeFailureException::class])
    @Operation
    suspend fun send(msg: String): String {
        val message = Message(body = msg, id = messageId++, from = env.nodeId)
        receivedMessages[env.nodeId][message.id] = 1
        undeliveredMessages[env.nodeId].add(message)
        deliver(env.nodeId)
        env.broadcast(message)
        return msg
    }
}


class BroadcastTest {
    @Test
    fun test() {
        LinChecker.check(
            Peer::class
                .java, DistributedOptions<Message, Message>().requireStateEquivalenceImplCheck
                (false).threads
                (3).setMaxNumberOfFailedNodes { it / 2 }.supportRecovery(false)
                .invocationsPerIteration(300).iterations(100).verifier(EpsilonVerifier::class.java)
                .messageOrder(MessageOrder.SYNCHRONOUS)
        )
    }

    @Test
    fun testNoFailures() {
        LinChecker.check(
            Peer::class
                .java, DistributedOptions<Message, Message>().requireStateEquivalenceImplCheck
                (false).actorsPerThread(2).threads
                (3).invocationsPerIteration(300).iterations(100).verifier(EpsilonVerifier::class.java)
        )
    }
}