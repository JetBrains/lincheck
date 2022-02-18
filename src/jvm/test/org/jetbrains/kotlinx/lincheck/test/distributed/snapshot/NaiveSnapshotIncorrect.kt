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

package org.jetbrains.kotlinx.lincheck.test.distributed.snapshot

import org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.NodeEnvironment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import kotlin.math.abs


@OpGroupConfig(name = "observer", nonParallel = true)
class NaiveSnapshotIncorrect(private val env: NodeEnvironment<Message>) : Node<Message> {
    private var currentSum = 0
    private val semaphore = Signal()
    private val replies = Array<SnapshotPart?>(env.nodes) { null }

    @Volatile
    private var gotSnapshot = false

    override fun onMessage(message: Message, sender: Int) {
        when (message) {
            is Transaction -> {
                currentSum += message.amount
            }
            is SnapshotRequest -> {
                env.send(SnapshotPart(currentSum), sender)
            }
            is SnapshotPart -> {
                replies[sender] = message
            }
        }
        checkAllRepliesReceived()
    }


    private fun checkAllRepliesReceived() {
        if (replies.filterNotNull().size == env.nodes - 1) {
            gotSnapshot = true
            semaphore.signal()
        }
    }

    @Operation
    fun sendMoney(to: Int, sum: Int) {
        val receiver = abs(to) % env.nodes
        if (to == env.id) return
        currentSum -= sum
        env.send(Transaction(sum), receiver)
    }

    override fun stateRepresentation(): String {
        val res = StringBuilder()
        res.append("[${env.id}]: Cursum ${currentSum}\n")
        replies.forEachIndexed { i, c -> res.append("[${env.id}]: reply[$i] $c\n") }
        return res.toString()
    }

    @Operation(group = "observer", cancellableOnSuspension = false)
    suspend fun totalBalance(): Int {
        val state = currentSum
        val marker = SnapshotRequest(env.id)
        env.broadcast(marker)
        while (!gotSnapshot) {
            semaphore.await()
        }
        val res = replies.filterNotNull().sumOf { it.amount } + state
        gotSnapshot = false
        replies.fill(null)
        return res
    }
}
