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
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.createDistributedOptions
import org.jetbrains.kotlinx.lincheck.test.distributed.examples.raft.*
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
/*
class WaitingServer(private val env: Environment<Boolean, Unit>) : Node<Boolean, Unit> {
    var counter = 0
    override fun onMessage(message: Boolean, sender: Int) {
        counter++
        if (counter == 2) {
            env.send(false, sender)
        }
        if (counter == 4) {
            println("Sending true")
            env.send(true, sender)
            counter = 0
        }
    }

}

class WaitingClient(private val env: Environment<Boolean, Unit>) : Node<Boolean, Unit> {
    private val responseChannel = Channel<Boolean>(UNLIMITED)
    override fun onMessage(message: Boolean, sender: Int) {
        responseChannel.offer(message)
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun operation() : Boolean {
        var count = 0
        while (true) {
            count++
            var response = false to count
            env.send(true, env.getAddressesForClass(WaitingServer::class.java)!![0])
            val res = env.withTimeout(RaftClient.OPERATION_TIMEOUT) {
                val msg = responseChannel.receive()
                response = msg to count
                env.recordInternalEvent("Inside timeout $msg")
                println("Inside timeout $msg, $response")
            }
            println("Exit loop $res, response=$response")
            if (res) {
                println("Exit loop")
            }
            env.recordInternalEvent("Continue loop $res")
            if (response.first) return response.first
        }
    }
}

class WaitingSpec : VerifierState() {
    override fun extractState() = true
    suspend fun operation() = true
}

class TimeoutTest {
    private fun options() = createDistributedOptions<Boolean>()
        .nodeType(WaitingClient::class.java, 1, false)
        .nodeType(WaitingServer::class.java, 1, )
        .requireStateEquivalenceImplCheck(false)
        .sequentialSpecification(WaitingSpec::class.java)
        .storeLogsForFailedScenario("wait.txt")
        .actorsPerThread(1)
        .invocationsPerIteration(1)
        .minimizeFailedScenario(false)
        .iterations(1)

    @Test
    fun test() = options().check()
}*/