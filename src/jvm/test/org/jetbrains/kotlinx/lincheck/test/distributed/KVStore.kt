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

package org.jetbrains.kotlinx.lincheck.test.distributed

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.distributed.CrashMode
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.NodeEnvironment
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

sealed class Message
data class PutRequest(val key: Int, val value: Int) : Message()
data class PutResponse(val prevValue: Int?) : Message()
data class GetRequest(val key: Int) : Message()
data class GetResponse(val value: Int?) : Message()

class Client(val env: NodeEnvironment<Message>) : Node<Message> {
    private val server = env.getAddresses<Server>()[0]
    private val resultsChannel = Channel<Int?>(UNLIMITED)

    override fun onMessage(message: Message, sender: Int) {
        when (message) {
            is PutResponse -> resultsChannel.offer(message.prevValue)
            is GetResponse -> resultsChannel.offer(message.value)
        }
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun get(key: Int): Int? {
        env.send(GetRequest(key), server)
        return resultsChannel.receive()
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun put(key: Int, value: Int): Int? {
        env.send(PutRequest(key, value), server)
        return resultsChannel.receive()
    }
}

class Server(val env: NodeEnvironment<Message>) : Node<Message> {
    private val storage = mutableMapOf<Int, Int>()

    override fun onMessage(message: Message, sender: Int) {
        when (message) {
            is PutRequest -> env.send(PutResponse(storage.put(message.key, message.value)), sender)
            is GetRequest -> env.send(GetResponse(storage[message.key]), sender)
        }
    }
}

class SeqSpec : VerifierState() {
    val storage = mutableMapOf<Int, Int>()
    suspend fun put(key: Int, value: Int) = storage.put(key, value)
    suspend fun get(key: Int) = storage[key]
    override fun extractState(): Any = storage
}

class KVStoreTest {
    private fun commonOptions() =
        DistributedOptions<Message>()
            .sequentialSpecification(SeqSpec::class.java)
            .invocationsPerIteration(30_000)
            .iterations(10)
            .addNodes<Server>(nodes = 1)

    @Test
    fun `incorrect algorithm with crashes`() {
        val failure = commonOptions()
            .addNodes<Client>(
                nodes = 3,
                crashMode = CrashMode.RECOVER_ON_CRASH,
                maxUnavailableNodes = { it }
            ).minimizeFailedScenario(false)
            .checkImpl(Client::class.java)
        assert(failure is IncorrectResultsFailure)
    }

    @Test
    fun `incorrect algorithm without crashes`() = commonOptions()
        .addNodes<Client>(nodes = 3)
        .check(Client::class.java)
}
