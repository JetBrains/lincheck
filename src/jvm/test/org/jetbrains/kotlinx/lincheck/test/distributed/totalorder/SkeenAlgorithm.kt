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

package org.jetbrains.kotlinx.lincheck.test.distributed.totalorder

import kotlinx.coroutines.channels.Channel
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test

sealed class Message {
    abstract val clock: Int
}

data class BroadcastMessage(
    val sender: Int,
    val id: Int,
    override val clock: Int,
    val finalized: Boolean,
    val time: Int
) :
    Message() {
    /**
     * If messages have the same [sender] and [id] they are equal.
     */
    override fun equals(other: Any?): Boolean {
        if (other !is BroadcastMessage) return false
        return sender == other.sender && id == other.id
    }

    override fun hashCode(): Int {
        var result = sender
        result = 31 * result + id
        return result
    }
}

data class Reply(override val clock: Int) : Message()


fun <E> MutableSet<E>.addOrUpdate(element: E) {
    remove(element)
    add(element)
}

class SkeenAlgorithm(env: Environment<Message>) : OrderCheckNode(env) {
    private var clock = 0
    private var opId = 0
    private val resChannel = Channel<Int>(Channel.UNLIMITED)
    private val replyTimes = mutableListOf<Int>()
    private val messages = mutableSetOf<BroadcastMessage>()

    override fun onMessage(message: Message, sender: Int) {
        clock = maxOf(clock, message.clock) + 1
        when (message) {
            is BroadcastMessage -> {
                messages.addOrUpdate(message)
                if (!message.finalized) env.send(Reply(++clock), sender)
                deliver()
            }
            is Reply -> {
                replyTimes.add(message.clock)
                if (replyTimes.size == env.nodes - 1) {
                    resChannel.offer(replyTimes.maxOf { it })
                }
            }
        }
    }

    override fun stateRepresentation() =
        "clock=$clock, messages=$messages, replies=$replyTimes, delivered=${delivered}"

    private fun deliver() {
        while (true) {
            var msg = messages.minByOrNull { it.time }
            if (msg?.finalized != true || messages.any { it.time == msg?.time && !it.finalized }) return
            msg = messages.filter { it.time == msg?.time }.minByOrNull { it.sender }
            messages.remove(msg)
            delivered.add(msg!!)
        }
    }

    @Operation(cancellableOnSuspension = false) // TODO: please make `cancellableOnSuspension = false` by default
    suspend fun broadcast() {
        replyTimes.clear()
        ++clock
        val msg = BroadcastMessage(sender = env.id, id = opId++, clock = clock, finalized = false, time = clock)
        env.broadcast(msg)
        val maxTime = resChannel.receive()
        val finalMsg = BroadcastMessage(
            sender = env.id, id = msg.id, clock = ++clock, finalized = true, time = maxTime
        )
        env.broadcast(finalMsg)
    }
}

class SkeenTest {
    private fun commonOptions() =
        DistributedOptions<Message>()
            .actorsPerThread(3)
            .invocationsPerIteration(500_000)
            .iterations(1)
            .verifier(EpsilonVerifier::class.java)
            .minimizeFailedScenario(false)

    @Test
    fun `correct algorithm`() = commonOptions()
        .addNodes<SkeenAlgorithm>(nodes = 3, minNodes = 2)
        .check(SkeenAlgorithm::class.java)

    @Test
    fun `incorrect algorithm`() {
        val failure = commonOptions()
            .addNodes<SkeenAlgorithmIncorrect>(nodes = 3, minNodes = 2)
            //.operation(SkeenAlgorithm::broadcast)
            .minimizeFailedScenario(false)
            .checkImpl(SkeenAlgorithmIncorrect::class.java)
        assert(failure != null)
    }
}