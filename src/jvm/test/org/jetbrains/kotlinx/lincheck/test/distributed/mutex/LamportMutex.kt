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
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.NodeEnvironment
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder.ASYNCHRONOUS
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import org.jetbrains.kotlinx.lincheck.paramgen.NodeIdGen
import org.junit.Test
import java.lang.Integer.max
import kotlin.coroutines.suspendCoroutine

sealed class MutexMessage {
    abstract val msgTime: Int
}

data class Req(override val msgTime: Int, val reqTime: Int) : MutexMessage()
data class Ok(override val msgTime: Int) : MutexMessage()
data class Rel(override val msgTime: Int) : MutexMessage()

sealed class MutexEvent
object Lock : MutexEvent()
object Unlock : MutexEvent()

class LamportMutex(private val env: NodeEnvironment<MutexMessage>) : Node<MutexMessage> {
    private val inf = Int.MAX_VALUE
    private var clock = 0 // logical time
    private var inCS = false // are we in critical section?
    private val req = IntArray(env.nodes) { inf } // time of last REQ message
    private val ok = IntArray(env.nodes) // time of last OK message
    private val signal = Signal()

    override fun onMessage(message: MutexMessage, sender: Int) {
        val time = message.msgTime
        clock = max(clock, time) + 1
        when (message) {
            is Req -> {
                req[sender] = message.reqTime
                env.send(Ok(++clock), sender)
            }
            is Ok -> ok[sender] = time
            is Rel -> req[sender] = inf
        }
        checkInCS()
    }


    override fun stateRepresentation() = "clock=${clock}, inCS=${inCS}, req=${req.toList()}, ok=${ok.toList()}"

    private fun checkInCS() {
        val myReqTime = req[env.id]
        if (myReqTime == inf || inCS) {
            return
        }
        for (i in 0 until env.nodes) {
            if (i == env.id) continue
            if (req[i] < myReqTime || req[i] == myReqTime && i < env.id) return
            if (ok[i] <= myReqTime) return
        }
        inCS = true
        signal.signal()
    }

    @Operation(blocking = true, cancellableOnSuspension = false)
    suspend fun lock(@Param(gen = NodeIdGen::class) nodeId: Int) {
        if (req[env.id] != inf) {
            suspendCoroutine<Unit> { }
        }
        val myReqTime = ++clock
        req[env.id] = myReqTime
        env.broadcast(Req(++clock, myReqTime))
        if (env.nodes == 1) {
            inCS = true
            return
        }
        while (!inCS) {
            signal.await()
        }
        env.recordInternalEvent(Lock)
    }

    @Operation
    fun unlock(@Param(gen = NodeIdGen::class) nodeId: Int) {
        if (!inCS) {
            return
        }
        inCS = false
        req[env.id] = inf
        env.recordInternalEvent(Unlock)
        env.broadcast(Rel(++clock))
    }
}

class LamportMutexTest {
    private fun commonOptions() = DistributedOptions<MutexMessage>()
        .addNodes<LamportMutex>(nodes = 4, minNodes = 2)
        .sequentialSpecification(MutexSpecification::class.java)
        .actorsPerThread(3)
        .invocationsPerIteration(30_000)
        .iterations(10)
        .minimizeFailedScenario(true)

    @Test
    fun `correct algorithm`() = commonOptions().check(LamportMutex::class.java)

    @Test
    fun `correct algorithm without FIFO`() {
        val failure = commonOptions().messageOrder(ASYNCHRONOUS).checkImpl(LamportMutex::class.java)
        assert(failure != null)
    }
}

