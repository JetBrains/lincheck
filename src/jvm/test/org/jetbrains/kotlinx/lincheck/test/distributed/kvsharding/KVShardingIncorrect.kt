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

import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.stress.LogLevel
import org.jetbrains.kotlinx.lincheck.distributed.stress.logMessage
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test

sealed class KVMessage(val id: Int, val isRequest: Boolean)
class GetRequest(val key: String, id: Int) : KVMessage(id, true)
class GetResponse(val value: String?, id: Int) : KVMessage(id, false)
class PutRequest(val key: String, val value: String, id: Int) : KVMessage(id, true)
class PutResponse(val previousValue: String?, id: Int) : KVMessage(id, false)
object Recover : KVMessage(0, true)

data class KVEntry(val key: String, val value: String)

class KVShardingIncorrect(val env: Environment<KVMessage, KVEntry>) : Node<KVMessage> {
    private var msgId = 0
    private val semaphore = Semaphore(1, 1)
    private var response: KVMessage? = null
    private var delegate: Int? = null
    private var appliedOperations = Array(env.numberOfNodes) {
        mutableMapOf<Int, KVMessage>()
    }

    private fun getNodeForKey(key: String) =
        ((key.hashCode() % env.numberOfNodes) + env.numberOfNodes) % env.numberOfNodes

    private fun saveToLog(key: String, value: String): String? {
        val log = env.log
        val index = log.indexOfFirst { it.key == key }
        return if (index == -1) {
            log.add(KVEntry(key, value))
            null
        } else {
            val res = log[index].value
            log[index] = KVEntry(key, value)
            res
        }
    }

    private fun getFromLog(key: String): String? {
        val log = env.log
        val index = log.indexOfFirst { it.key == key }
        return if (index == -1) {
            null
        } else {
            log[index].value
        }
    }

    @Operation
    suspend fun put(key: String, value: String): String? {
        val node = getNodeForKey(key)
        if (node == env.nodeId) {
            return saveToLog(key, value)
        }
        val request = PutRequest(key, value, msgId++)
        return (send(request, node) as PutResponse).previousValue
    }

    private suspend fun send(request: KVMessage, receiver: Int): KVMessage {
        response = null
        delegate = receiver
        while (true) {
            env.send(request, receiver)
            semaphore.acquire()
            logMessage(LogLevel.ALL_EVENTS) {
                "[${env.nodeId}]: After semaphore acquire"
            }
            response ?: continue
            delegate = null
            return response!!
        }
    }

    override suspend fun recover() {
        logMessage(LogLevel.ALL_EVENTS) {
            "[${env.nodeId}]: Recover, should send messages"
        }
        env.broadcast(Recover)
    }

    @Operation
    suspend fun get(key: String): String? {
        val node = getNodeForKey(key)
        if (node == env.nodeId) {
            return getFromLog(key)
        }
        val request = GetRequest(key, msgId++)
        return (send(request, node) as GetResponse).value
    }

    override suspend fun onMessage(message: KVMessage, sender: Int) {
        if (message is Recover) {
            if (sender == delegate) {
                semaphore.release()
            }
            return
        }
        if (!message.isRequest) {
            if (message.id != msgId - 1) return
            if (response == null) {
                response = message
                semaphore.release()
            }
            return
        }
        val msg = appliedOperations[sender][message.id] ?: when (message) {
            is GetRequest -> GetResponse(getFromLog(message.key), message.id)
            is PutRequest -> PutResponse(saveToLog(message.key, message.value), message.id)
            else -> throw IllegalStateException()
        }
        appliedOperations[sender][message.id] = msg
        env.send(msg, sender)
    }
}

class SingleNode {
    private val storage = HashMap<String, String>()

    @Operation
    suspend fun put(key: String, value: String): String? {
        return storage.put(key, value)
    }

    @Operation
    suspend fun get(key: String): String? {
        return storage[key]
    }
}

class KVShardingTest {
    @Test
    fun testNoFailuresIncorrect() {
        LinChecker.check(
            KVShardingIncorrect::class
                .java, DistributedOptions<KVMessage, KVEntry>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(SingleNode::class.java).threads
                (3).invocationsPerIteration(30).iterations(1000)
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun testIncorrect() {
        LinChecker.check(
            KVShardingIncorrect::class
                .java, DistributedOptions<KVMessage, KVEntry>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(SingleNode::class.java).actorsPerThread(2).threads
                (3).invocationsPerIteration(30).setMaxNumberOfFailedNodes { it / 2 }
                .iterations(1000).supportRecovery(true).verifier(EpsilonVerifier::class.java)
        )
    }

    @Test
    fun testNoFailures() {
        LinChecker.check(
            KVSharding::class
                .java, DistributedOptions<KVMessage, KVLogEntry>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(SingleNode::class.java).threads
                (3).invocationsPerIteration(30).iterations(1000)
        )
    }

    @Test
    fun test() {
        LinChecker.check(
            KVSharding::class
                .java, DistributedOptions<KVMessage, KVLogEntry>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(SingleNode::class.java).actorsPerThread(2).threads
                (3).invocationsPerIteration(300).setMaxNumberOfFailedNodes { it / 2 }
                .iterations(1000).supportRecovery(true).verifier(EpsilonVerifier::class.java)
        )
    }
}