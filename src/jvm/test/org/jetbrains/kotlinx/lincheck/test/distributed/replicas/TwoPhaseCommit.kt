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

package org.jetbrains.kotlinx.lincheck.test.distributed.replicas

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.junit.Test


data class KVLogEntry(val index: Int, val key: String, val value: String, var commited: Boolean = true)


sealed class Message
data class ClientGetRequest(val id: Int, val key: String) : Message()
data class ClientPutRequest(val id: Int, val key: String, val value: String) : Message()
data class PutRequest(val index: Int, val client: Int, val clientId: Int, val key: String, val value: String) :
    Message()

enum class Status { COMMIT, ABORT }
data class ClientGetResponse(val id: Int, val value: String? = null) : Message()
data class PutResponse(val id: Int, val client: Int, val clientId: Int, val status: Status) : Message()
data class ApplyRequest(val id: Int, val client: Int, val clientId: Int, val apply: Boolean) : Message()
data class ApplyResponse(val id: Int, val client: Int, val clientId: Int, val isApplied: Boolean) : Message()

class AbortOperationException : Exception()

class Client(val env: Environment<Message, Unit>) : Node<Message> {
    private val signal = Signal()
    private var id = 0
    private val results = mutableMapOf<Int, Message>()
    private val leader = env.getAddressesForClass(LeaderNode::class.java)!![0]
    private var receiver: Int? = null

    override suspend fun onMessage(message: Message, sender: Int) {
        when (message) {
            is PutResponse -> results[message.id] = message
            is ClientGetResponse -> results[message.id] = message
            else -> throw IllegalArgumentException("Unexpected message $message for ${env.nodeId} from $sender")
        }
        signal.signal()
    }

    override suspend fun onNodeUnavailable(iNode: Int) {
        if (iNode == receiver) signal.signal()
    }

    @Operation(handleExceptionsAsResult = [AbortOperationException::class])
    suspend fun put(key: String, value: String) {
        val index = id++
        val request = ClientPutRequest(index, key, value)
        receiver = leader
        while (true) {
            env.send(request, leader)
            env.withTimeout(3) {
                signal.await()
            }
            if (results.containsKey(index)) {
                if ((results[index]!! as PutResponse).status == Status.COMMIT) return
            }
        }
    }

    @Operation
    suspend fun get(key: String): String? {
        val index = id++
        val request = ClientGetRequest(index, key)
        val nodes = env.getAddressesForClass(ReplicaNode::class.java)
        receiver = nodes!!.random()
        while (true) {
            env.send(request, receiver!!)
            env.withTimeout(5) {
                signal.await()
            }
            if (results.containsKey(index)) {
                return (results[index]!! as ClientGetResponse).value
            }
            receiver = nodes.random()
        }
    }
}


class LeaderNode(val env: Environment<Message, KVLogEntry>) : Node<Message> {
    private val putResponses = mutableMapOf<Int, Int>()
    private val applyResponses = mutableMapOf<Int, Int>()
    private val clientRequests = Array(env.numberOfNodes) {
        mutableMapOf<Int, Signal>()
    }
    private val replicaCount = env.getAddressesForClass(ReplicaNode::class.java)!!.size

    override suspend fun onMessage(message: Message, sender: Int) {
        when (message) {
            is ClientPutRequest -> {
                val index = env.log.size
                env.log.add(KVLogEntry(index, message.key, message.value))
                clientRequests[sender][message.id] = Signal()
                broadcast(PutRequest(index, sender, message.id, message.key, message.value))
                awaitRequest(message, sender)
            }
            is PutResponse -> {
                if (message.status == Status.COMMIT) {
                    putResponses[message.id] = putResponses[message.id]?.inc() ?: 1
                    if (putResponses[message.id] == replicaCount) {
                        env.log.lastOrNull { it.index == message.id }!!.commited = true
                        broadcast(ApplyRequest(message.id, message.client, message.clientId, true))
                    }
                }
            }
            is ApplyResponse -> {
                if (!message.isApplied) {
                    broadcast(ApplyRequest(message.id, message.client, message.clientId, false))
                } else {
                    applyResponses[message.id] = applyResponses[message.id]?.inc() ?: 1
                    if (applyResponses[message.id] == replicaCount) {
                        clientRequests[message.client][message.clientId]?.signal()
                    }
                }
            }
            else -> throw IllegalArgumentException("Unexpected message type")
        }
    }

    private suspend fun awaitRequest(clientRequest: ClientPutRequest, sender: Int) {
        if (!env.withTimeout(20) {
                clientRequests[sender][clientRequest.id]!!.await()
            }) {
            env.send(PutResponse(clientRequest.id, sender, clientRequest.id, Status.ABORT), sender)
        } else {
            env.send(PutResponse(clientRequest.id, sender, clientRequest.id, Status.COMMIT), sender)
        }
    }

    private suspend fun broadcast(msg: Message) {
        env.getAddressesForClass(ReplicaNode::class.java)?.forEach { env.send(msg, it) }
    }
}

class ReplicaNode(val env: Environment<Message, KVLogEntry>) : Node<Message> {
    override suspend fun onMessage(message: Message, sender: Int) {
        when (message) {
            is ClientGetRequest -> env.send(ClientGetResponse(message.id, get(message.key)), sender)
            is PutRequest -> {
                if (!env.log.any { it.index == message.index }) {
                    env.log.add(KVLogEntry(message.index, message.key, message.value))
                }
                env.send(PutResponse(message.index, message.client, message.clientId, Status.COMMIT), sender)
            }
            is ApplyRequest -> {
                val entry = env.log.lastOrNull { it.index == message.id }
                entry?.commited = message.apply
                env.send(
                    ApplyResponse(message.id, message.client, message.clientId, message.apply && entry != null),
                    sender
                )
            }
            else -> throw IllegalArgumentException("Unexpected message")
        }
    }

    private fun get(key: String) = env.log.filter { it.commited }.lastOrNull { it.key == key }?.value
}

class TwoPhaseCommitTest {
    private fun createOptions() = DistributedOptions<Message, KVLogEntry>()
        .requireStateEquivalenceImplCheck(false)
        .sequentialSpecification(ReplicaSpecification::class.java)
        .nodeType(LeaderNode::class.java, 1)
        .nodeType(ReplicaNode::class.java, 3)
        .threads(2)
        .invocationsPerIteration(100)
        .iterations(300)
        .invocationTimeout(50_000)

    @Test
    fun testNoFailures() {
        LinChecker.check(
            Client::class.java,
            createOptions()
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun test() {
        LinChecker.check(
            Client::class.java,
            createOptions().setMaxNumberOfFailedNodes { 1 }
                .supportRecovery(RecoveryMode.ALL_NODES_RECOVER)
        )
    }
}