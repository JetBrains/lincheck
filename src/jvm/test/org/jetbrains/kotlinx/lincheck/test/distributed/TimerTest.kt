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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.NodeEnvironment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import org.junit.Test

sealed class TimerMessage
object TimerPing : TimerMessage()
object TimerPong : TimerMessage()
object Heartbeat : TimerMessage()

class TimerNode(private val env: NodeEnvironment<TimerMessage>) : Node<TimerMessage> {
    companion object {
        const val HEARTBEAT_PING_RATE = 3
    }

    private val pingSignal = Signal()
    private val pongSignal = Signal()
    private var heartbeatCnt = 0
    private var shouldReply = false
    override fun onMessage(message: TimerMessage, sender: Int) {
        when (message) {
            is Heartbeat -> {
                heartbeatCnt++
                if (heartbeatCnt == HEARTBEAT_PING_RATE) {
                    pingSignal.signal()
                    if (shouldReply) {
                        shouldReply = false
                        env.send(TimerPong, sender)
                    }
                }
            }
            is TimerPing -> {
                heartbeatCnt = 0
                shouldReply = true
            }
            is TimerPong -> pongSignal.signal()
        }
    }

    override fun onStart() {
        env.setTimer("HEARTBEAT", 1) {
            env.broadcast(Heartbeat)
        }
    }

    @Operation
    suspend fun ping() {
        env.send(TimerPing, 1 - env.id)
        pongSignal.await()
    }
}


class TimerTest {
    @Test
    fun test() = DistributedOptions<TimerMessage>()
        .addNodes<TimerNode>(nodes = 2)
        .requireStateEquivalenceImplCheck(false)
        .invocationsPerIteration(10_000)
        .iterations(1)
        .sequentialSpecification(PingPongMock::class.java)
        .actorsPerThread(2)
        .check(TimerNode::class.java)
}