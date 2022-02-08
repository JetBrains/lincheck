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

package org.jetbrains.kotlinx.lincheck.test.distributed.mutex

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder.ASYNCHRONOUS
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import org.jetbrains.kotlinx.lincheck.paramgen.NodeIdGen
import org.junit.Test
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max


class RickartAgrawalaMutex(private val env: Environment<MutexMessage>) : Node<MutexMessage> {
    companion object {
        @Volatile
        private var cnt = 0
    }

    private val inf = Int.MAX_VALUE
    private var clock = 0 // logical time
    private var inCS = false // are we in critical section?
    private val req = IntArray(env.nodes) { inf } // time of last REQ message
    private val ok = IntArray(env.nodes) // time of last OK message
    private val pendingOk = BooleanArray(env.nodes)
    private val signal = Signal()

    override fun onMessage(message: MutexMessage, sender: Int) {
        val time = message.msgTime
        clock = max(clock, time) + 1
        when (message) {
            is Req -> {
                val reqTime = message.reqTime
                req[sender] = reqTime
                val myReqTime = req[env.id]
                if (reqTime < myReqTime || reqTime == myReqTime && sender < env.id) {
                    env.send(Ok(++clock), sender)
                } else {
                    pendingOk[sender] = true
                }
            }
            is Ok -> {
                ok[sender] = time
                req[sender] = inf
            }
            else -> throw RuntimeException("Unexpected message type")
        }
        checkInCS()
    }

    private fun checkInCS() {
        val myReqTime = req[env.id]
        if (myReqTime == inf) return // did not request CS, do nothing
        if (inCS) return // already in CS, do nothing
        for (i in 0 until env.nodes) {
            if (i != env.id) {
                if (req[i] < myReqTime || req[i] == myReqTime && i < env.id) return // better ticket
                if (ok[i] <= myReqTime) return // did not Ok our request
            }
        }
        inCS = true
        signal.signal()
    }

    @Operation(cancellableOnSuspension = false, blocking = true)
    suspend fun lock(@Param(gen = NodeIdGen::class) nodeId: Int) {
        if (inCS) {
            suspendCoroutine<Unit> { }
        }
        val myReqTime = ++clock
        req[env.id] = myReqTime
        env.broadcast(Req(++clock, myReqTime))
        if (env.nodes == 1) {
            inCS = true
        } else {
            signal.await()
            check(inCS)
        }
        env.recordInternalEvent(Lock)
    }

    @Operation
    fun unlock(@Param(gen = NodeIdGen::class) nodeId: Int) {
        if (!inCS) return
        inCS = false
        req[env.id] = inf
        env.recordInternalEvent(Unlock)
        for (i in 0 until env.nodes) {
            if (pendingOk[i]) {
                pendingOk[i] = false
                env.send(Ok(++clock), i)
            }
        }
    }
}

class RickartAgrawalaMutexTest {
    private fun commonOptions() = DistributedOptions<MutexMessage>()
        .addNodes<RickartAgrawalaMutex>(nodes = 4, minNodes = 2)
        .sequentialSpecification(MutexSpecification::class.java)
        .actorsPerThread(3)
        .invocationsPerIteration(50_000)
        .iterations(10)

    @Test
    fun `algorithm with FIFO`() = commonOptions().check(RickartAgrawalaMutex::class.java)

    @Test
    fun `algorithm without FIFO`() = commonOptions()
        .messageOrder(ASYNCHRONOUS)
        .check(RickartAgrawalaMutex::class.java)
}