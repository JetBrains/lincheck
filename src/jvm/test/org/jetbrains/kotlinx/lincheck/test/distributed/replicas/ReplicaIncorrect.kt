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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.NodeEnvironment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import kotlin.random.Random

sealed class KVMessage
data class PutKVRequest(val key: Int, val value: Int) : KVMessage()
data class PutKVEntry(val key: Int, val value: Int) : KVMessage()
data class GetKVRequest(val key: Int) : KVMessage()
data class GetKVResponse(val value: Int?) : KVMessage()

class ReplicaIncorrect(private val env: NodeEnvironment<KVMessage>) : Node<KVMessage> {
    private val storage = mutableMapOf<Int, Int>()
    override fun onMessage(message: KVMessage, sender: Int) {
        when (message) {
            is PutKVRequest -> {
                storage[message.key] = message.value
                env.broadcastToGroup<ReplicaIncorrect>(PutKVEntry(message.key, message.value))
            }
            is PutKVEntry -> storage[message.key] = message.value
            is GetKVRequest -> env.send(GetKVResponse(storage[message.key]), sender)
            else -> throw IllegalArgumentException("Unexpected message")
        }
    }
}

class ClientIncorrect(private val env: NodeEnvironment<KVMessage>) : Node<KVMessage> {
    private val signal = Signal()
    private var res: GetKVResponse? = null
    private val rand = Random(env.id)

    private fun getRandomReplica() = env.getAddresses<ReplicaIncorrect>().random(rand)

    @Operation(cancellableOnSuspension = false)
    suspend fun get(key: Int): Int? {
        val replica = getRandomReplica()
        env.send(GetKVRequest(key), replica)
        signal.await()
        return res!!.value
    }

    @Operation
    fun put(key: Int, value: Int) {
        val replica = getRandomReplica()
        env.send(PutKVRequest(key, value), replica)
    }

    override fun onMessage(message: KVMessage, sender: Int) {
        if (message is GetKVResponse) {
            res = message
            signal.signal()
        } else {
            throw IllegalArgumentException("Unexpected exception")
        }
    }
}

class ReplicaSpecification : VerifierState() {
    private val storage = mutableMapOf<Int, Int>()

    @Operation
    suspend fun get(key: Int) = storage[key]

    @Operation
    fun put(key: Int, value: Int) {
        storage[key] = value
    }

    override fun extractState(): Any = storage
}

class ReplicaIncorrectTest {
    @Test
    fun test() {
        val failure = DistributedOptions<KVMessage>()
            .addNodes<ReplicaIncorrect>(nodes = 4, minNodes = 1)
            .addNodes<ClientIncorrect>(nodes = 3, minNodes = 1)
            .invocationsPerIteration(100_000)
            .iterations(30)
            .sequentialSpecification(ReplicaSpecification::class.java)
            .checkImpl(ClientIncorrect::class.java)
        assert(failure is IncorrectResultsFailure)
    }
}

