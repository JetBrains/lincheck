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

import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.event.Event
import org.jetbrains.kotlinx.lincheck.distributed.event.MessageReceivedEvent
import org.jetbrains.kotlinx.lincheck.distributed.event.MessageSentEvent
import org.jetbrains.kotlinx.lincheck.distributed.event.NodeCrashEvent
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test
import java.util.*

data class Message(val body: String, val id: Int, val from: Int)


fun List<Event>.correctProcesses() =
    groupBy { it.iNode }.filter { !it.value.any { p -> p is NodeCrashEvent } }
        .map { it.key }

fun <Message> List<Event>.sentMessages(iNode: Int) =
    filter { it.iNode == iNode }.filterIsInstance<MessageSentEvent<Message>>()

fun <Message> List<Event>.receivedMessages(iNode: Int) =
    filter { it.iNode == iNode }.filterIsInstance<MessageReceivedEvent<Message>>()

fun <Message> List<Message>.isDistinct(): Boolean = distinctBy { System.identityHashCode(it) } == this

fun List<Event>.isCorrect(iNode: Int): Boolean = correctProcesses().contains(iNode)

abstract class AbstractPeer(protected val env: Environment<Message, Message>) : Node<Message, Message> {
    override fun validate(events: List<Event>, logs: Array<List<Message>>) {
        check(env.log.isDistinct()) { "Process ${env.nodeId} contains repeated messages" }
        // If message m from process s was delivered, it was sent by process s before.
        env.log.forEach { m ->
            check(events.sentMessages<Message>(m.from).map { it.message }.contains(m)) {
                m
            }
        }
        // If the correct process sent message m, it should deliver m.
        if (events.isCorrect(env.nodeId)) {
            events.sentMessages<Message>(env.nodeId).map { it.message }.forEach { m -> check(m in env.log) }
        }
        // If the message was delivered to one process, it was delivered to all correct processes.
        env.log.forEach { m ->
            events.correctProcesses().forEach { check(logs[it].contains(m)) { "$m, $it, ${env.nodeId}" } }
        }
        // If some process sent m1 before m2, every process which delivered m2 delivered m1.
        val localMessagesOrder = Array(env.numberOfNodes) { i ->
            env.log.filter { it.from == i }
                .map { m ->
                    events.sentMessages<Message>(i).map { it.message }.filter { it.from == i }.distinctBy { it.id }
                        .indexOf(m)
                }
        }
        localMessagesOrder.forEach {
            check(it.sorted() == it)
            if (it.isNotEmpty()) {
                for (i in 0..it.last()) {
                    check(i in it)
                }
            }
        }
    }
}

class Peer(env: Environment<Message, Message>) : AbstractPeer(env) {
    private val receivedMessages = Array<HashMap<Int, Int>>(env.numberOfNodes) { HashMap() }
    private var messageId = 0
    private val undeliveredMessages = Array<PriorityQueue<Message>>(env.numberOfNodes) {
        PriorityQueue { x, y -> x.id - y.id }
    }
    private val lastDeliveredId = Array(env.numberOfNodes) { -1 }

    override fun stateRepresentation(): String {
        return "Received messages=${receivedMessages.toList()}, undelivered ${undeliveredMessages.toList()}, " +
                "logs=${env.log.toList()}"
    }

    private fun deliver(sender: Int) {
        while (undeliveredMessages[sender].isNotEmpty()) {
            val lastMessage = undeliveredMessages[sender].peek()
            if (lastMessage.id != lastDeliveredId[sender] + 1 || receivedMessages[sender][lastMessage.id]!! < (env.numberOfNodes + 1) / 2) {
                return
            }
            undeliveredMessages[sender].remove()
            lastDeliveredId[sender]++
            env.log.add(lastMessage)
        }
        //println("Finish deliver")
    }


    override fun onMessage(message: Message, sender: Int) {
        //println("In on message")
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
    private fun createOptions() = DistributedOptions<Message, Message>()
        .requireStateEquivalenceImplCheck(false)
        .actorsPerThread(3)
        .invocationsPerIteration(30_000)
        .iterations(1)
        .verifier(EpsilonVerifier::class.java)

    @Test
    fun test() = createOptions()
        .nodeType(Peer::class.java, 2, 4)
        .setMaxNumberOfFailedNodes { it / 2 }
        .crashMode(CrashMode.NO_RECOVERIES)
        .setTestMode(TestingMode.MODEL_CHECKING)
        .minimizeFailedScenario(false)
        .storeLogsForFailedScenario("broadcast.txt")
        .check()

    @Test(expected = LincheckAssertionError::class)
    fun testMoreFailures() = createOptions()
        .nodeType(Peer::class.java, 5)
        .setMaxNumberOfFailedNodes { it / 2 + 1 }
        .crashMode(CrashMode.NO_RECOVERIES)
        .invocationsPerIteration(50_000)
        .storeLogsForFailedScenario("broadcast.txt")
        .minimizeFailedScenario(false)
        .setTestMode(TestingMode.MODEL_CHECKING)
        .check()

    @Test
    fun testNoFailures() = createOptions()
        //.storeLogsForFailedScenario("broadcast_nof.txt")
        .nodeType(PeerIncorrect::class.java, 3)
        .setTestMode(TestingMode.MODEL_CHECKING)
        .invocationsPerIteration(100_000)
        .check()

    @Test(expected = LincheckAssertionError::class)
    fun testIncorrect() = createOptions()
        .storeLogsForFailedScenario("broadcast_incorrect.txt")
        .setMaxNumberOfFailedNodes { it / 2 }
        .crashMode(CrashMode.NO_RECOVERIES)
        .actorsPerThread(1)
        .setTestMode(TestingMode.MODEL_CHECKING)
        //.invocationsPerIteration(500_000)
        // .minimizeFailedScenario(true)
        .nodeType(PeerIncorrect::class.java, 4)
        .check()
}