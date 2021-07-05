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

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.*
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

class LamportMutex(private val env: Environment<MutexMessage, Unit>) : Node<MutexMessage> {
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

    @Validate
    fun validate() {
        val events = env.events().map { it.filterIsInstance<InternalEvent>() }
        val locks = events.flatMap { it.filter { it.message == "Lock" } }
        for (i in locks.indices) {
            for (j in 0..i) {
                check(locks[i].clock.happensBefore(locks[j].clock) || locks[j].clock.happensBefore(locks[i].clock))
            }
        }
    }

    //override fun stateRepresentation() = "clock=${clock}, inCS=${inCS}, req=${req.toList()}, ok=${ok.toList()}"

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
        env.recordInternalEvent("Acquire lock")
    }

    @Operation(cancellableOnSuspension = false)
    fun unlock() {
        if (!inCS) {
            return
        }
        inCS = false
        req[env.nodeId] = inf
        env.recordInternalEvent("Release lock")
        env.broadcast(Rel(++clock))
    }
}

class LamportMutexTest {
    private fun createOptions() = DistributedOptions<MutexMessage, Unit>()
        .requireStateEquivalenceImplCheck(false)
        .sequentialSpecification(MutexSpecification::class.java)
        .threads(2)
        .actorsPerThread(1)
        .invocationsPerIteration(3_000)
        .iterations(10)
       // .storeLogsForFailedScenario("lamport.txt")
        //.minimizeFailedScenario(false)

    @Test
    fun testSimple() {
        LinChecker.check(
            LamportMutex::class.java,
            createOptions()
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun testNoFifo() {
        LinChecker.check(
            LamportMutex::class.java,
            createOptions().messageOrder(MessageOrder.ASYNCHRONOUS)
                //.storeLogsForFailedScenario("lamport_nofifo.txt")
        )
    }
}

