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

package org.jetbrains.kotlinx.lincheck.test.distributed.serverclientstorage

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.createDistributedOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

sealed class PingPongMessage
object Ping : PingPongMessage()
object Pong : PingPongMessage()

class PingPongNode(val env: Environment<PingPongMessage, Unit>) : Node<PingPongMessage, Unit> {
    val channel = Channel<PingPongMessage>(UNLIMITED)
    var hasResult = false
    override fun onMessage(message: PingPongMessage, sender: Int) {
        when (message) {
            is Ping -> env.send(Pong, sender)
            is Pong -> {
                channel.offer(message)
                hasResult = true
            }
        }
    }

    @Operation
    suspend fun ping(): Boolean {
        hasResult = false
        while (true) {
            env.send(Ping, 0)
            val res = env.withTimeout(50) {
                channel.receive()
                println("[${env.nodeId}]: Inside timeout")
            }
            println("[${env.nodeId}]: Exit timeout $res")
            if (hasResult) return true
        }
    }
}

class PingPongMock : VerifierState() {
    override fun extractState() = true
    suspend fun ping() = true
}

class SimpleTimeoutTest {
    @Test
    fun test() = createDistributedOptions<PingPongMessage>()
        .nodeType(PingPongNode::class.java, 2)
        .requireStateEquivalenceImplCheck(false)
        .networkReliable(false)
        .invocationsPerIteration(100)
        .iterations(100)
        .sequentialSpecification(PingPongMock::class.java)
        .actorsPerThread(2)
        .check()
}