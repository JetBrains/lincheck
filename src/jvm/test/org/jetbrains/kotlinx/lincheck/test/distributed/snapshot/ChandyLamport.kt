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

package org.jetbrains.kotlinx.lincheck.test.distributed.snapshot

import org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import kotlin.math.abs

/**
 * A message for Chandy-Lamport algorithm.
 */
sealed class Message

/**
 * A transaction between two nodes.
 */
data class Transaction(val sum: Int) : Message()

/**
 * A marker which indicates that the snapshot of the system is taken.
 */
data class Marker(val initializer: Int, val token: Int) : Message()

/**
 * When the node state is stored, it is sent to the snapshot initializer.
 */
data class Reply(val state: Int, val token: Int) : Message()

/**
 * Stored in the channels while snapshot is taken.
 */
sealed class State

/**
 * The value which was received.
 */
data class Value(val sum: Int) : State()

/**
 * Indicates that writing to this channel is finished.
 */
object Empty : State()

@OpGroupConfig(name = "observer", nonParallel = true)
class ChandyLamport(private val env: Environment<Message, Unit>) : Node<Message, Unit> {
    private var currentSum = 100
    private var semaphore = Signal()
    private var state = 0
    private var token = 0
    private var markerCount = 0
    private var marker: Marker? = null
    private val replies = Array<Reply?>(env.numberOfNodes) { null }
    private val channels = Array<MutableList<State>>(env.numberOfNodes) { mutableListOf() }
    private var gotSnapshot = false

    override fun onMessage(message: Message, sender: Int) {
        when (message) {
            is Transaction -> {
                currentSum += message.sum
                if (marker != null && (channels[sender].isEmpty() || channels[sender].last() !is Empty)) {
                    channels[sender].add(Value(message.sum))
                }
            }
            is Marker -> {
                channels[sender].add(Empty)
                markerCount++
                if (marker == null) {
                    state = currentSum
                    marker = message
                    env.broadcast(message)
                }
                if (markerCount == env.numberOfNodes - 1) {
                    val res = finishSnapshot()
                    env.send(Reply(res, marker!!.token), marker!!.initializer)
                    marker = null
                }
            }
            is Reply -> {
                replies[sender] = message
                checkAllRepliesReceived()
            }
        }
    }

    private fun finishSnapshot(): Int {
        val stored =
            channels.sumOf { it.filterIsInstance(Value::class.java).sumOf { v -> v.sum } }
        val res = state + stored
        markerCount = 0
        channels.forEach { it.clear() }
        return res
    }

    private fun checkAllRepliesReceived() {
        if (replies.filterNotNull().size == env.numberOfNodes) {
            gotSnapshot = true
            semaphore.signal()
        }
    }

    @Operation(cancellableOnSuspension = false)
    fun transaction(to: Int, sum: Int) {
        val receiver = abs(to) % env.numberOfNodes
        if (receiver == env.nodeId) return
        currentSum -= sum
        env.send(Transaction(sum), receiver)
    }

    override fun stateRepresentation(): String {
        val res = StringBuilder()
        res.append("[${env.nodeId}]: Current sum ${currentSum}\n")
        res.append("[${env.nodeId}]: State $state\n")
        channels.forEachIndexed { i, c -> res.append("[${env.nodeId}]: channel[$i] $c\n") }
        replies.forEachIndexed { i, c -> res.append("[${env.nodeId}]: reply[$i] $c\n") }
        return res.toString()
    }

    @Operation(group = "observer", cancellableOnSuspension = false)
    suspend fun snapshot(): Int {
        state = currentSum
        marker = Marker(env.nodeId, token++)
        env.broadcast(marker!!)
        if (env.numberOfNodes == 1) {
            return state
        }
        while (!gotSnapshot) {
            semaphore.await()
        }
        val res = replies.map { it as Reply }.sumOf { it.state }
        marker = null
        gotSnapshot = false
        replies.fill(null)
        return res / env.numberOfNodes + res % env.numberOfNodes
    }
}

class MockSnapshot() : VerifierState() {
    @Operation()
    fun transaction(to: Int, sum: Int) {
    }

    @Operation
    suspend fun snapshot() = 100
    override fun extractState(): Any = 100
}

class SnapshotTest {
    private fun commonOptions() = createDistributedOptions<Message>()
        .sequentialSpecification(MockSnapshot::class.java)
        .actorsPerThread(3)
        .invocationsPerIteration(30_000)
        .iterations(10)

    @Test
    fun `correct algorithm`() = commonOptions()
        .addNodes<ChandyLamport>(nodes = 4, minNodes = 2)
        .check(ChandyLamport::class.java)

    @Test
    fun `correct algorithm without FIFO`() {
        val failure = commonOptions()
            .addNodes<ChandyLamport>(nodes = 4, minNodes = 2)
            .messageOrder(MessageOrder.ASYNCHRONOUS)
            .minimizeFailedScenario(false)
            .checkImpl(ChandyLamport::class.java)
        assert(failure is IncorrectResultsFailure)
    }

    @Test
    fun `incorrect algorithm`() {
        val failure = commonOptions()
            .addNodes<NaiveSnapshotIncorrect>(nodes = 4, minNodes = 2)
            .sequentialSpecification(MockSnapshot::class.java)
            .minimizeFailedScenario(false)
            .checkImpl(NaiveSnapshotIncorrect::class.java)
        println(failure)
        assert(failure is IncorrectResultsFailure)
    }
}