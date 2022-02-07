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


package org.jetbrains.kotlinx.lincheck.test.distributed.kvsharding

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.distributed.CrashMode.RECOVER_ON_CRASH
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.NodeWithStorage
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

sealed class KVMessage {
    abstract val id: Int
    abstract val isRequest: Boolean
}

data class GetRequest(val key: String, override val id: Int) : KVMessage() {
    override val isRequest: Boolean = true
}

data class GetResponse(val value: String?, override val id: Int) : KVMessage() {
    override val isRequest: Boolean = false
}

data class PutRequest(val key: String, val value: String, override val id: Int) : KVMessage() {
    override val isRequest: Boolean = true
}

data class PutResponse(val previousValue: String?, override val id: Int) : KVMessage() {
    override val isRequest: Boolean = false
}

object Recover : KVMessage() {
    override val isRequest: Boolean = true
    override val id: Int = 0
}

class Storage {
    var opId: Int = 0
    val keyValues = mutableListOf<KVLog>()

    fun getPrevValue(index: Int) = keyValues[index].request.value
}

data class KVLog(val request: PutRequest, val prev: String?)

class Shard(env: Environment<KVMessage>) : NodeWithStorage<KVMessage, Storage>(env) {
    private val semaphore = Signal()
    private var response: KVMessage? = null
    private var delegate: Int? = null

    override fun stateRepresentation(): String {
        return "opId=${storage.opId}, delegate=$delegate, response=$response, log=${storage.keyValues}, hash=${hashCode()}"
    }

    private fun getNodeForKey(key: String) =
        ((key.hashCode() % env.nodes) + env.nodes) % env.nodes

    private fun saveToLog(request: PutRequest): String? {
        val present = storage.keyValues.lastOrNull { it.request == request }
        if (present != null) {
            return present.prev
        }
        val index = storage.keyValues.indexOfLast { it.request.key == request.key }
        val prev = if (index == -1) {
            null
        } else {
            storage.getPrevValue(index)
        }
        storage.keyValues.add(KVLog(request, prev))
        return prev
    }

    private fun getFromLog(key: String): String? {
        val index = storage.keyValues.indexOfLast { it.request.key == key }
        return if (index == -1) {
            null
        } else {
            storage.getPrevValue(index)
        }
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun put(key: String, value: String): String? {
        val opId = ++(storage.opId)
        env.recordInternalEvent("Operation id now is $opId, hash=${hashCode()} " + stateRepresentation())
        val node = getNodeForKey(key)
        val request = PutRequest(key, value, opId)
        if (node == env.id) {
            return saveToLog(request)
        }
        return (send(request, node) as PutResponse).previousValue
    }

    private suspend fun send(request: KVMessage, receiver: Int): KVMessage {
        response = null
        delegate = receiver
        while (true) {
            env.send(request, receiver)
            env.withTimeout(10) {
                semaphore.await()
            }
            response ?: continue
            delegate = null
            return response!!
        }
    }

    override fun recover() {
        storage.opId++
        env.broadcast(Recover)
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun get(key: String): String? {
        val opId = ++(storage.opId)
        val node = getNodeForKey(key)
        if (node == env.id) {
            return getFromLog(key)
        }
        val request = GetRequest(key, opId)
        return (send(request, node) as GetResponse).value
    }

    override fun onMessage(message: KVMessage, sender: Int) {
        if (message is Recover) {
            if (sender == delegate) {
                semaphore.signal()
            }
            return
        }
        if (!message.isRequest) {
            if (message.id != storage.opId) {
                return
            }
            if (response == null) {
                response = message
                semaphore.signal()
            }
            return
        }
        val msg = when (message) {
            is GetRequest -> GetResponse(getFromLog(message.key), message.id)
            is PutRequest -> PutResponse(saveToLog(message), message.id)
            else -> throw IllegalStateException()
        }
        env.send(msg, sender)
    }

    override fun createStorage(): Storage = Storage()
}

class SingleNode : VerifierState() {
    private val storage = mutableMapOf<String, String>()

    @Operation
    suspend fun put(key: String, value: String) = storage.put(key, value)

    @Operation
    suspend fun get(key: String) = storage[key]

    override fun extractState(): Any {
        return storage
    }
}

class KVShardingTest {
    private fun options() = DistributedOptions<KVMessage>()
        .sequentialSpecification(SingleNode::class.java)
        .invocationsPerIteration(30_000)
        .actorsPerThread(3)
        .minimizeFailedScenario(false)
        .iterations(10)

    @Test
    fun `correct algorithm`() = options()
        .addNodes<Shard>(
            nodes = 4,
            crashMode = RECOVER_ON_CRASH,
            maxUnavailableNodes = { it / 2 }
        ).check(Shard::class.java)

    @Test
    fun `incorrect algorithm without crashes`() = options()
        .addNodes<ShardMultiplePutToLog>(nodes = 4)
        .check(ShardMultiplePutToLog::class.java)

    @Test
    fun `incorrect algorithm`() {
        val failure = options()
            .addNodes<ShardMultiplePutToLog>(
                nodes = 4,
                crashMode = RECOVER_ON_CRASH,
                maxUnavailableNodes = { it / 2 }
            )
            .checkImpl(ShardMultiplePutToLog::class.java)
        assert(failure is IncorrectResultsFailure)
    }
}