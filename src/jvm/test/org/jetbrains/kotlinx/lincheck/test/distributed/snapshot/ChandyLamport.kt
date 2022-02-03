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
data class Transaction(val amount: Int) : Message()

/**
 * A marker which indicates that the snapshot of the system is taken.
 */
data class SnapshotRequest(val requestedBy: Int) : Message()

/**
 * When the node state is stored, it is sent to the snapshot initializer.
 */
data class SnapshotPart(val amount: Int) : Message()

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

private class SnapshotOperation(
    amountOnSnapshotStart: Int
) : Signal() {
    var receivedSnapshotParts = 0
    var snapshot = amountOnSnapshotStart
}

@OpGroupConfig(name = "observer", nonParallel = true)
class ChandyLamport(private val env: Environment<Message, Unit>) : Node<Message, Unit> {
    private var currentAmount = 0
    private var snapshotRequest: SnapshotOperation? = null
    private var marker: SnapshotRequest? = null
    private val channels = Array<MutableList<State>>(env.nodes) { mutableListOf() }

    override fun onMessage(message: Message, sender: Int) {
        when (message) {
            is Transaction -> {
                currentAmount += message.amount
                if (marker != null && (channels[sender].isEmpty() || channels[sender].last() !is Empty)) {
                    channels[sender].add(Value(message.amount))
                }
            }
            is SnapshotRequest -> {
                channels[sender].add(Empty)
                receivedSnapshotParts++
                if (marker == null) {
                    amountOnSnapshotStart = currentAmount
                    marker = message
                    env.broadcast(message)
                }
                if (receivedSnapshotParts == env.nodes - 1) {
                    val res = finishSnapshot()
                    env.send(SnapshotPart(res, marker!!.token), marker!!.requestedBy)
                    marker = null
                }
            }
            is SnapshotPart -> {
                replies[sender] = message
                checkAllRepliesReceived()
            }
        }
    }

    private fun finishSnapshot(): Int {
        val stored =
            channels.sumOf { it.filterIsInstance(Value::class.java).sumOf { v -> v.sum } }
        val res = amountOnSnapshotStart + stored
        receivedSnapshotParts = 0
        channels.forEach { it.clear() }
        return res
    }

    private fun checkAllRepliesReceived() {
        if (replies.filterNotNull().size == env.nodes) {
            signal.signal()
        }
    }

    @Operation(cancellableOnSuspension = false)
    fun sendMoney(to: Int, amount: Int) {
        val receiver = abs(to) % env.nodes
        if (receiver == env.nodeId) return
        currentAmount -= amount
        env.send(Transaction(amount), receiver)
    }

    override fun stateRepresentation(): String {
        val res = StringBuilder()
        res.append("[${env.nodeId}]: Current sum ${currentAmount}\n")
        res.append("[${env.nodeId}]: State $amountOnSnapshotStart\n")
        channels.forEachIndexed { i, c -> res.append("[${env.nodeId}]: channel[$i] $c\n") }
        replies.forEachIndexed { i, c -> res.append("[${env.nodeId}]: reply[$i] $c\n") }
        return res.toString()
    }

    @Operation(group = "observer", cancellableOnSuspension = false)
    suspend fun totalBalance(): Int {
        amountOnSnapshotStart = currentAmount
        marker = SnapshotRequest(env.nodeId, token++)
        env.broadcast(marker!!)
        if (env.nodes == 1) {
            return amountOnSnapshotStart
        }
        signal.await()
        val res = replies.map { it as SnapshotPart }.sumOf { it.state }
        marker = null
        replies.fill(null)
        return res
    }
}

class SequentialSnapshotAlgorithm : VerifierState() {
    fun transaction(to: Int, sum: Int) {}
    // TODO: allow non-suspend implementations in sequential specifications
    suspend fun snapshot() = 0 // never changes
    override fun extractState() = Unit
}

class SnapshotAlgorithmTest {
    private fun commonOptions() = createDistributedOptions<Message>()
        .sequentialSpecification(SequentialSnapshotAlgorithm::class.java)
        .actorsPerThread(3) // please rename; e.g., to "operations/request per node"
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
            .sequentialSpecification(SequentialSnapshotAlgorithm::class.java)
            .minimizeFailedScenario(false)
            .checkImpl(NaiveSnapshotIncorrect::class.java)
        assert(failure is IncorrectResultsFailure)
    }
}