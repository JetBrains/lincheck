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

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.withLock
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.test.distributed.mutex.Counter
import org.jetbrains.kotlinx.lincheck.test.distributed.mutex.LamportMutex
import org.jetbrains.kotlinx.lincheck.test.distributed.mutex.MutexMessage
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

sealed class Message
data class Transaction(val sum: Int) : Message()
data class Marker(val initializer: Int, val token: Int) : Message()
data class Reply(val state: Int, val token: Int) : Message()
data class CurState(val state: Int) : Message()
data class OpStateReceive(val state: Int, val sum : Int) : Message()
data class OpStateSend(val state: Int, val sum : Int) : Message()

sealed class State
class Value(val sum: Int) : State()
object Empty : State()

@OpGroupConfig(name = "observer", nonParallel = true)
class ChandyLamport(private val env: Environment<Message, Unit>) : Node<Message> {
    private val currentSum = atomic(100)
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private var state = 0
    private var token = 0
    private var markerCount = 0
    private var marker: Marker? = null
    private val replies = Array<Reply?>(env.numberOfNodes) { null }
    private val channels = Array<MutableList<State>>(env.numberOfNodes) { mutableListOf() }

    @Volatile
    private var gotSnapshot = false

    override fun onMessage(message: Message, sender: Int) {
        lock.withLock {
            when (message) {
                is Transaction -> {
                    currentSum.getAndAdd(message.sum)
                    env.sendLocal(OpStateReceive(currentSum.value, message.sum))
                    if (marker != null && (channels[sender].isEmpty() || channels[sender].last() !is Empty)) {
                        channels[sender].add(Value(message.sum))
                    }
                }
                is Marker -> {
                    channels[sender].add(Empty)
                    markerCount++
                    if (marker == null) {
                        state = currentSum.value
                        env.sendLocal(CurState(state))
                        marker = message
                        broadcast(message)
                    } else {
                        check(marker == message) {
                            "Execution is not non parallel"
                        }
                        if (markerCount == env.numberOfNodes - 1) {
                            val res = finishSnapshot()
                            env.send(Reply(res, marker!!.token), marker!!.initializer)
                            marker = null
                        }
                    }
                }
                is Reply -> {
                    replies[sender] = message
                }
            }
            checkAllRepliesReceived()
        }
    }

    private fun finishSnapshot(): Int {
        val stored =
            channels.map { it.filterIsInstance(Value::class.java).map { v -> v.sum }.sum() }.sum()

        val res = state + stored
        markerCount = 0
        channels.forEach { it.clear() }
        return res
    }


    private fun checkAllRepliesReceived() {
        if (replies.filterNotNull().size == env.numberOfNodes) {
            gotSnapshot = true
            condition.signal()
            //println("[${env.nodeId}]: Here $gotSnapshot")
            condition.signalAll()
        }
    }

    @Operation()
    fun transaction(sum: Int) {
        lock.withLock {
            val receiver = (0 until env.numberOfNodes).filter { it != env.nodeId }.shuffled()[0]
            currentSum.getAndAdd(-sum)
            env.sendLocal(OpStateSend(currentSum.value, sum))
            env.send(Transaction(sum), receiver)
        }
    }

    @StateRepresentation
    fun stateRepresentation(): String {
        val res = StringBuilder()
        res.append("[${env.nodeId}]: Cursum ${currentSum.value}\n")
        res.append("[${env.nodeId}]: State $state\n")
        channels.forEachIndexed { i, c -> res.append("[${env.nodeId}]: channel[$i] $c\n") }
        replies.forEachIndexed { i, c -> res.append("[${env.nodeId}]: reply[$i] $c\n") }
        return res.toString()
    }

    @Operation(group = "observer")
    fun snapshot(): Int {
        //println("[${env.nodeId}]: Start snapshot")
        lock.withLock {
            state = currentSum.value
            env.sendLocal(CurState(state))
            marker = Marker(env.nodeId, token++)
            broadcast(marker!!)
            while (!gotSnapshot) {
                // println("[${env.nodeId}]: Sleep")
                condition.await()
                // println("[${env.nodeId}]: Woke up")
            }
            val res = replies.map { it as Reply }.map { it.state }.sum()
            marker = null
            gotSnapshot = false
            //println("Res is $res")
            replies.fill(null)
            return res / env.numberOfNodes + res % env.numberOfNodes
        }

        //println("[${env.nodeId}]: Made snapshot $res")
        // check(res % env.numberOfNodes == 0)
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

class MockSnapshot {
    @Operation()
    fun transaction(sum: Int) {
    }

    @Operation
    fun snapshot() = 100
}

class SnapshotTest {
    @Test
    fun testSimple() {
        LinChecker.check(
            ChandyLamport::class
                .java, DistributedOptions<Message, Unit>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(MockSnapshot::class.java).threads
                (3).messageOrder(MessageOrder.FIFO)
                .invocationsPerIteration(50).iterations(1000)
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun testNaiveIncorrect() {
        LinChecker.check(
            NaiveSnapshotIncorrect::class
                .java, DistributedOptions<Message, Unit>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(MockSnapshot::class.java).threads
                (3).messageOrder(MessageOrder.FIFO)
                .invocationsPerIteration(50).iterations(1000)
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun testChandyLamportIncorrect() {
        LinChecker.check(
           ChandyLamportIncorrect::class
                .java, DistributedOptions<Message, Unit>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(MockSnapshot::class.java).threads
                (3).messageOrder(MessageOrder.FIFO)
                .invocationsPerIteration(50).iterations(1000)
        )
    }
}