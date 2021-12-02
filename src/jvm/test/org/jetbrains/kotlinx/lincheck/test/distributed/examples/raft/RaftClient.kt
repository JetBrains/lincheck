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
import org.jetbrains.kotlinx.lincheck.distributed.CrashMode
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.createDistributedOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import kotlin.random.Random

class RaftClient(private val env: Environment<RaftMessage, PersistentStorage>) : Node<RaftMessage, PersistentStorage> {
    companion object {
        const val OPERATION_TIMEOUT = 120
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
        while (true) {
            var response: ClientResult? = null
            val nodeToSend = leader ?: servers.random(random)
            env.send(ClientRequest(command), nodeToSend)
            env.withTimeout(OPERATION_TIMEOUT) {
                val msg = responseChannel.receive()
                if (msg is ClientResult && opId == msg.commandId.opId) {
                    response = msg
                    return@withTimeout
                }
                if (msg is NotALeader) {
                    leader = msg.leaderId
                }
            }
            if (response != null) return response!!.res
        }
    }

    @Operation
    suspend fun get(key: String): String? =
        executeOperation { GetCommand(CommandId(client = env.nodeId, opId = opId), key) }

    @Operation
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
        .nodeType(RaftClient::class.java, 1, false)
        .nodeType(RaftServer::class.java, 1, 3)
        //.crashMode(CrashMode.ALL_NODES_RECOVER)
        //.setMaxNumberOfFailedNodes(RaftServer::class.java) { (it - 1) / 2 }
        .requireStateEquivalenceImplCheck(false)
        .sequentialSpecification(RaftSpecification::class.java)
        .storeLogsForFailedScenario("raft.txt")

    @Test
    fun test() = options().check()
}