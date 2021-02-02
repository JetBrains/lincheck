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

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.withLock
import org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import java.util.concurrent.locks.ReentrantLock


@OpGroupConfig(name = "observer", nonParallel = true)
class NaiveSnapshotIncorrect(private val env: Environment<Message, Unit>) : Node<Message> {
    private val currentSum = atomic(100)
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private var token = 0
    private val replies = Array<Reply?>(env.numberOfNodes) { null }

    @Volatile
    private var gotSnapshot = false

    override fun onMessage(message: Message, sender: Int) {
        lock.withLock {
            when (message) {
                is Transaction -> {
                    currentSum.getAndAdd(message.sum)
                }
                is Marker -> {
                    env.send(Reply(currentSum.value, message.token), sender)
                }
                is Reply -> {
                    replies[sender] = message
                }
            }
            checkAllRepliesReceived()
        }
    }


    private fun checkAllRepliesReceived() {
        if (replies.filterNotNull().size == env.numberOfNodes - 1) {
            gotSnapshot = true
            condition.signal()
            //println("[${env.nodeId}]: Here $gotSnapshot")
        }
    }

    @Operation()
    fun transaction(sum: Int) {
        lock.withLock {
            val receiver = (0 until env.numberOfNodes).filter { it != env.nodeId }.shuffled()[0]
            currentSum.getAndAdd(-sum)
            env.send(Transaction(sum), receiver)
        }
    }

    @StateRepresentation
    fun stateRepresentation(): String {
        val res = StringBuilder()
        res.append("[${env.nodeId}]: Cursum ${currentSum.value}\n")
        replies.forEachIndexed { i, c -> res.append("[${env.nodeId}]: reply[$i] $c\n") }
        return res.toString()
    }

    @Operation(group = "observer")
    fun snapshot(): Int {
        //println("[${env.nodeId}]: Start snapshot")

        lock.withLock {
            val state = currentSum.value
            //env.sendLocal(CurState(state))
            val marker = Marker(env.nodeId, token++)
            broadcast(marker)
            while (!gotSnapshot) {
                // println("[${env.nodeId}]: Sleep")
                condition.await()
                // println("[${env.nodeId}]: Woke up")
            }
            val res = replies.filterNotNull().map { it as Reply }.map { it.state }.sum() + state
            //println("[${env.nodeId}]: Made snapshot $res")
            gotSnapshot = false
            println("Res is $res")

            replies.fill(null)
            return res / env.numberOfNodes + res % env.numberOfNodes
        }
    }

    private fun broadcast(msg: Message) {
        for (i in 0 until env.numberOfNodes) {
            if (i == env.nodeId) {
                continue
            }
            env.send(msg, i)
        }
    }
}

