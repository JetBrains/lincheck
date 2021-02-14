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

import kotlinx.coroutines.delay
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.queue.cntGet
import org.jetbrains.kotlinx.lincheck.distributed.stress.LogLevel
import org.jetbrains.kotlinx.lincheck.distributed.stress.NodeFailureException
import org.jetbrains.kotlinx.lincheck.distributed.stress.cntNullGet
import org.jetbrains.kotlinx.lincheck.distributed.stress.logMessage
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test
import java.util.*
import kotlin.random.Random

data class Message(val body: String, val id: Int, val from: Int)

/**
 *
 */
class Peer(private val env: Environment<Message, Unit>) : Node<Message> {
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
            env.sendLocal(lastMessage)
        }
    }

    @Validate
    fun validateResults() {
        // All messages were delivered at most once.
        check(env.localMessages().isDistinct()) { "Process ${env.nodeId} contains repeated messages" }
        // If message m from process s was delivered, it was sent by process s before.
        env.localMessages().forEach { m -> check(env.sentMessages(m.from).map { it.message }.contains(m)) }
        // If the correct process sent message m, it should deliver m.
        if (env.isCorrect()) {
            env.localMessages().forEach { m -> check(env.sentMessages().map { it.message }.contains(m)) }
        }
        // If the message was delivered to one process, it was delivered to all correct processes.
        //println(env.correctProcesses())
        env.localMessages().forEach { m ->
            env.correctProcesses().forEach { check(env.localMessages(it).contains(m)) { env.localMessages() } }
        }
        // If some process sent m1 before m2, every process which delivered m2 delivered m1.
        val localMessagesOrder = Array(env.numberOfNodes) { i ->
            env.localMessages().filter { it.from == i }.map { m -> env.sentMessages(i).map { it.message }.indexOf(m) }
        }
        localMessagesOrder.forEach { check(it.sorted() == it) }
    }

    @StateRepresentation
    fun state(): String {
        return env.localMessages().toString()
    }

    override suspend fun onMessage(message: Message, sender: Int) {
        val msgId = message.id
        val from = message.from
        if (!receivedMessages[from].contains(msgId)) {
            receivedMessages[from][msgId] = 1
            undeliveredMessages[from].add(message)
            if (sender != env.nodeId) {
                env.broadcast(message)
            }
        } else {
            //println("[${env.nodeId}]: $message received ${receivedMessages[from][msgId]} times")
            receivedMessages[from][msgId] = receivedMessages[from][msgId]!! + 1
        }
        deliver(from)
    }

    //@Operation(handleExceptionsAsResult = [NodeFailureException::class])
    @Operation
    suspend fun send(msg: String) : String {
        logMessage(LogLevel.ALL_EVENTS) {
            println("[${env.nodeId}]: Start operation")
        }
        val message = Message(body = msg, id = messageId++, from = env.nodeId)
        env.broadcast(message)
        return msg
    }
}


class BroadcastTest {
    @Test
    fun test() {
        LinChecker.check(Peer::class
            .java, DistributedOptions<Message, Unit>().requireStateEquivalenceImplCheck
            (false).threads
            (3).setMaxNumberOfFailedNodes { it / 2 }.supportRecovery(false)
            .invocationsPerIteration(30).iterations(1000).verifier(EpsilonVerifier::class.java)
            .messageOrder(MessageOrder.SYNCHRONOUS))
        println("Get $cntGet, nulls $cntNullGet, not null ${cntGet - cntNullGet}")
    }

    @Test
    fun testNoFailures() {
        LinChecker.check(
            Peer::class
                .java, DistributedOptions<Message, Unit>().requireStateEquivalenceImplCheck
                (false).threads
                (3).invocationsPerIteration(30).iterations(1000).verifier(EpsilonVerifier::class.java)
        )
        println("Get $cntGet, nulls $cntNullGet, not null ${cntGet - cntNullGet}")
    }
}