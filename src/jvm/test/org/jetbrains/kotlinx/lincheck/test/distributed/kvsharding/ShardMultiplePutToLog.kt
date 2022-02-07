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

package org.jetbrains.kotlinx.lincheck.test.distributed.kvsharding

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.NodeWithStorage
import org.jetbrains.kotlinx.lincheck.distributed.Signal

class IncorrectStorage {
    var opId = 0
    val keyValues = mutableMapOf<String, String>()
}

class ShardMultiplePutToLog(env: Environment<KVMessage>) : NodeWithStorage<KVMessage, IncorrectStorage>(env) {
    private val semaphore = Signal()
    private var response: KVMessage? = null
    private var delegate: Int? = null
    private var appliedOperations = Array(env.nodes) {
        mutableMapOf<Int, KVMessage>()
    }

    private fun getNodeForKey(key: String) =
        ((key.hashCode() % env.nodes) + env.nodes) % env.nodes

    private fun saveToLog(key: String, value: String): String? {
        return storage.keyValues.put(key, value)
    }

    private fun getFromLog(key: String): String? = storage.keyValues[key]

    @Operation(cancellableOnSuspension = false)
    suspend fun put(key: String, value: String): String? {
        val opId = ++(storage.opId)
        val node = getNodeForKey(key)
        if (node == env.id) {
            return saveToLog(key, value)
        }
        val request = PutRequest(key, value, opId)
        return (send(request, node) as PutResponse).previousValue
    }

    private suspend fun send(request: KVMessage, receiver: Int): KVMessage {
        response = null
        delegate = receiver
        while (true) {
            env.send(request, receiver)
            semaphore.await()
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
            if (message.id != storage.opId) return
            if (response == null) {
                response = message
                semaphore.signal()
                env.recordInternalEvent("Signal")
            }
            return
        }
        // If the node has failed, applied operations are lost, and we can
        // put the same entry to log multiple times. This leads to incorrect results,
        // as 'put' returns previous value.
        val msg = appliedOperations[sender][message.id] ?: when (message) {
            is GetRequest -> GetResponse(getFromLog(message.key), message.id)
            is PutRequest -> PutResponse(saveToLog(message.key, message.value), message.id)
            else -> throw IllegalStateException()
        }
        appliedOperations[sender][message.id] = msg
        env.send(msg, sender)
    }

    override fun stateRepresentation(): String {
        return "opId=${storage.opId}, response=$response"
    }

    override fun createStorage(): IncorrectStorage {
        return IncorrectStorage()
    }
}