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
 * Amount of money sent between nodes.
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

private class SnapshotOperation(
    amountOnSnapshotStart: Int,
    val requestedBy: Int,
    val nodes: Int
) : Signal() {
    private var receivedSnapshotParts = 0
    var snapshot = amountOnSnapshotStart
    private val receivedRequests = mutableSetOf<Int>()

    /**
     * If the snapshot for this node is collected.
     */
    val finishPart: Boolean
        get() = receivedRequests.size == nodes - 1

    /**
     * Adds [amount] to snapshot if request wasn't receive from [sender] before.
     */
    fun addAmount(amount: Int, sender: Int) {
        if (receivedRequests.contains(sender)) return
        snapshot += amount
    }

    /**
     * Marks that the request was received from [sender].
     */
    fun addRequest(sender: Int) {
        receivedRequests.add(sender)
    }

    /**
     * Adds the snapshot part from other node.
     */
    fun addSnapshotPart(amount: Int) {
        receivedSnapshotParts++
        snapshot += amount
        // Checks that all parts are received.
        // The collection of the own snapshot is finished because of the FIFO message order.
        if (receivedSnapshotParts == nodes - 1) {
            signal()
        }
    }

    override fun toString(): String {
        return "Snapshot=$snapshot, receivedRequest=$receivedRequests, receivedParts=$receivedSnapshotParts"
    }
}

/**
 * Chandy Lamport algorithm.
 * 1. Snapshot initializer saves its current state and sends request to other nodes.
 * 2. When node receives request from another node it initializes the snapshot by its current amount,
 * broadcasts the request.
 * 3. If the snapshot is initialized on node A, and node A received the amount S from node B, and didn't receive the snapshot request from node B,
 * the S is added to snapshot of A.
 * 4. If node A, which didn't initialize the snapshot, received the request (n - 1) times,
 * it sends the resulting snapshot to initializer and clears the snapshot.
 * 5. If initializer receives snapshot parts from all other nodes it returns the result.
 */
@OpGroupConfig(name = "observer", nonParallel = true)
class ChandyLamport(private val env: Environment<Message>) : Node<Message> {
    private var currentAmount = 0
    private var snapshotOperation: SnapshotOperation? = null

    override fun onMessage(message: Message, sender: Int) {
        when (message) {
            is Transaction -> {
                currentAmount += message.amount
                // Adds amount to snapshot if necessary.
                snapshotOperation?.addAmount(message.amount, sender)
            }
            is SnapshotRequest -> {
                // When the request is received for the first time, the snapshot is initialized and
                // request is broadcast to other nodes.
                if (snapshotOperation == null) {
                    snapshotOperation = SnapshotOperation(currentAmount, message.requestedBy, env.nodes)
                    env.broadcast(message)
                }
                // Messages from sender will not be added to snapshot anymore.
                snapshotOperation!!.addRequest(sender)
                // Send the result to initializer and clear the snapshot if necessary.
                if (snapshotOperation!!.finishPart && snapshotOperation!!.requestedBy != env.id) {
                    env.send(SnapshotPart(snapshotOperation!!.snapshot), snapshotOperation!!.requestedBy)
                    snapshotOperation = null
                }
            }
            is SnapshotPart -> {
                snapshotOperation!!.addSnapshotPart(message.amount)
            }
        }
    }

    @Operation(cancellableOnSuspension = false)
    fun sendMoney(to: Int, amount: Int) {
        val receiver = abs(to) % env.nodes
        if (receiver == env.id) return
        currentAmount -= amount
        env.send(Transaction(amount), receiver)
    }

    @Operation(group = "observer", cancellableOnSuspension = false)
    suspend fun totalBalance(): Int {
        snapshotOperation = SnapshotOperation(currentAmount, env.id, env.nodes)
        val message = SnapshotRequest(env.id)
        env.broadcast(message)
        snapshotOperation!!.await()
        val res = snapshotOperation!!.snapshot
        snapshotOperation = null
        return res
    }

    override fun stateRepresentation(): String {
        return snapshotOperation.toString()
    }
}

class SequentialSnapshotAlgorithm : VerifierState() {
    fun sendMoney(to: Int, sum: Int) {}

    // TODO: allow non-suspend implementations in sequential specifications
    suspend fun totalBalance() = 0 // never changes
    override fun extractState() = Unit
}

class SnapshotAlgorithmTest {
    private fun commonOptions() = DistributedOptions<Message>()
        .sequentialSpecification(SequentialSnapshotAlgorithm::class.java)
        .actorsPerThread(3) // please rename; e.g., to "operations/request per node"
        .invocationsPerIteration(300_000)
        .iterations(10)

    @Test
    fun `correct algorithm`() = commonOptions()
        .addNodes<ChandyLamport>(nodes = 3, minNodes = 2)
        .storeLogsForFailedScenario("chandylamport.txt")
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