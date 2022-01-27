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

package org.jetbrains.kotlinx.lincheck.test.distributed.examples.raft

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.distributed.CrashMode.FINISH_OR_RECOVER_ON_CRASH
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.NetworkPartitionMode.COMPONENTS
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.createDistributedOptions
import org.jetbrains.kotlinx.lincheck.strategy.TaskLimitExceededFailure
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import kotlin.random.Random

class RaftClient(private val env: Environment<RaftMessage, PersistentStorage>) : Node<RaftMessage, PersistentStorage> {
    companion object {
        const val OPERATION_TIMEOUT = 200
    }

    private val servers = env.getAddressesForClass(RaftServer::class.java)!!
    private var opId = 0
    private val responseChannel = Channel<ResponseToClient>(UNLIMITED)
    private val random = Random(env.nodeId)

    override fun onMessage(message: RaftMessage, sender: Int) {
        if (message !is ResponseToClient) throw IllegalStateException("Unexpected message to client $message")
        responseChannel.offer(message)
    }

    private suspend fun executeOperation(createCommand: () -> Command): String? {
        opId++
        val command = createCommand()
        var leader: Int? = null
        var response: ClientResult? = null
        while (true) {
            val nodeToSend = leader ?: servers.random(random)
            env.send(ClientRequest(command), nodeToSend)
            leader = env.withTimeout(OPERATION_TIMEOUT) {
                val msg = responseChannel.receive()
                if (msg is ClientResult && opId == msg.commandId.opId) {
                    response = msg
                } else {
                    leader = if (msg is NotALeader) {
                        msg.leaderId
                    } else {
                        null
                    }
                }
                leader
            }
            if (response != null) return response!!.res
        }
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun get(key: String): String? =
        executeOperation { GetCommand(CommandId(client = env.nodeId, opId = opId), key) }

    @Operation(cancellableOnSuspension = false)
    suspend fun put(key: String, value: String): String? =
        executeOperation { PutCommand(CommandId(client = env.nodeId, opId = opId), key, value) }
}

class RaftSpecification() : VerifierState() {
    private val storage = mutableMapOf<String, String>()
    suspend fun get(key: String) = storage[key]
    suspend fun put(key: String, value: String) = storage.put(key, value)
    override fun extractState(): Any = storage
}

class RaftTest {
    private fun options() = createDistributedOptions<RaftMessage, PersistentStorage>(::PersistentStorage)
        .addNodes<RaftClient>(nodes = 3)
        .sequentialSpecification(RaftSpecification::class.java)
        .actorsPerThread(3)
        .sendCrashNotifications(false)
        .invocationsPerIteration(30_000)
        .minimizeFailedScenario(false)
        .iterations(10)

    @Test
    fun `correct algorithm`() = options()
        .addNodes<RaftServer>(
            nodes = 5,
            minNodes = 1,
            crashMode = FINISH_OR_RECOVER_ON_CRASH,
            networkPartition = COMPONENTS,
            maxUnavailableNodes = { (it + 1) / 2 - 1 })
        .check(RaftClient::class.java)

    @Test
    fun `correct algorithm with too much unavailable nodes`() {
        val failure = options().addNodes<RaftServer>(
            nodes = 5,
            crashMode = FINISH_OR_RECOVER_ON_CRASH,
            networkPartition = COMPONENTS,
            maxUnavailableNodes = { (it + 1) / 2 + 1 })
            .checkImpl(RaftClient::class.java)
        assert(failure is TaskLimitExceededFailure)
    }
}