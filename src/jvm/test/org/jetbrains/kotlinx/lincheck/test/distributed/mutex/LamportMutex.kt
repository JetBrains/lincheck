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

import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.event.Event
import org.jetbrains.kotlinx.lincheck.distributed.event.InternalEvent
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test
import java.lang.Integer.max
import kotlin.coroutines.suspendCoroutine

sealed class MutexMessage(val msgTime: Int)

class Req(msgTime: Int, val reqTime: Int) : MutexMessage(msgTime) {
    override fun toString(): String {
        return "REQ($msgTime, $reqTime)"
    }
}

class Ok(msgTime: Int) : MutexMessage(msgTime) {
    override fun toString(): String {
        return "OK($msgTime)"
    }
}

class Rel(msgTime: Int) : MutexMessage(msgTime) {
    override fun toString(): String {
        return "REL($msgTime)"
    }
}

abstract class MutexNode<Message> : Node<Message, Unit> {
    override fun validate(events: List<Event>, logs: List<Unit>) {
        val locksAndUnlocks = events.filterIsInstance<InternalEvent>()
        for (i in locksAndUnlocks.indices step 2) {
            check(locksAndUnlocks[i].attachment == "Lock")
            if (i + 1 < locksAndUnlocks.size) {
                check(
                    locksAndUnlocks[i + 1].attachment == "Unlock" && locksAndUnlocks[i].iNode == locksAndUnlocks[i + 1].iNode && locksAndUnlocks[i].clock.happensBefore(
                        locksAndUnlocks[i + 1].clock
                    )
                )
            }
            if (i >= 1) {
                check(locksAndUnlocks[i - 1].clock.happensBefore(locksAndUnlocks[i].clock))
            }
        }
    }
}

class LamportMutex(private val env: Environment<MutexMessage, Unit>) : MutexNode<MutexMessage>() {
    private val inf = Int.MAX_VALUE
    private var clock = 0 // logical time
    private var inCS = false // are we in critical section?
    private val req = IntArray(env.numberOfNodes) { inf } // time of last REQ message
    private val ok = IntArray(env.numberOfNodes) // time of last OK message
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
        val myReqTime = req[env.nodeId]
        if (myReqTime == inf || inCS) {
            return
        }
        for (i in 0 until env.numberOfNodes) {
            if (i == env.nodeId) continue
            if (req[i] < myReqTime || req[i] == myReqTime && i < env.nodeId) return
            if (ok[i] <= myReqTime) return
        }
        inCS = true
        signal.signal()
    }

    @Operation(blocking = true, cancellableOnSuspension = false)
    suspend fun lock() {
        if (req[env.nodeId] != inf) {
            suspendCoroutine<Unit> { }
        }
        val myReqTime = ++clock
        req[env.nodeId] = myReqTime
        env.broadcast(Req(++clock, myReqTime))
        if (env.numberOfNodes == 1) {
            inCS = true
            return
        }
        while (!inCS) {
            signal.await()
        }
        env.recordInternalEvent("Lock")
    }

    @Operation(cancellableOnSuspension = false)
    fun unlock() {
        if (!inCS) {
            return
        }
        inCS = false
        req[env.nodeId] = inf
        env.recordInternalEvent("Unlock")
        env.broadcast(Rel(++clock))
    }
}

class LamportMutexTest {
    private fun createOptions() = createDistributedOptions<MutexMessage>()
        .nodeType(LamportMutex::class.java, 2, 3)
        .requireStateEquivalenceImplCheck(false)
        .threads(3)
        .verifier(EpsilonVerifier::class.java)
        .actorsPerThread(4)
        .invocationsPerIteration(30_000)
        .iterations(10)
        .storeLogsForFailedScenario("lamport.txt")
        .minimizeFailedScenario(true)

    @Test
    fun testSimple() = createOptions().check()

    @Test(expected = LincheckAssertionError::class)
    fun testNoFifo() = createOptions().messageOrder(MessageOrder.ASYNCHRONOUS).check()
}

