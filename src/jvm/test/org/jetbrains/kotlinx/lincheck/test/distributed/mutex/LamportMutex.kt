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

import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.stress.LogLevel
import org.jetbrains.kotlinx.lincheck.distributed.stress.logMessage
import org.junit.Test
import java.lang.Integer.max
import java.util.concurrent.locks.ReentrantLock

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
    companion object {
        private var counter = 0
    }

    private val inf = Int.MAX_VALUE
    private var clock = 0 // logical time
    private var inCS = false // are we in critical section?
    private val req = IntArray(env.numberOfNodes) { inf } // time of last REQ message
    private val ok = IntArray(env.numberOfNodes) // time of last OK message
    private val signal = Semaphore(1, 1)

    override suspend fun onMessage(message: MutexMessage, sender: Int) {
        val aaa = if (env.nodeId == sender) {
            "AAAAAAAAAAAAAAAAAAAA"
        } else {
            ""
        }
        logMessage(LogLevel.ALL_EVENTS) {
            "[${env.nodeId}]: $aaa On message $message, $sender"
        }
        val time = message.msgTime
        clock = max(clock, time) + 1
        when (message) {
            is Req -> {
                req[sender] = message.reqTime
                env.send(Ok(++clock), sender)
            }
            is Ok -> {
                ok[sender] = time
            }
            is Rel -> {
                req[sender] = inf
            }
            else -> throw RuntimeException("Unexpected message type")
        }
        checkInCS()
    }

    @Validate
    fun validate() {
        counter = 0
    }

    private fun checkInCS() {
        val myReqTime = req[env.nodeId]
        if (myReqTime == inf || inCS) {
            return
        }
        logMessage(LogLevel.ALL_EVENTS) {
            "[${env.nodeId}]: Check in CS myReqTime=$myReqTime, req=${req.toList()}, ok=${ok.toList()}"
        }
        for (i in 0 until env.numberOfNodes) {
            if (i == env.nodeId) {
                continue
            }
            if (req[i] < myReqTime || req[i] == myReqTime && i < env.nodeId) {
                return
            }
            if (ok[i] <= myReqTime) {
                return
            }
        }
        inCS = true
        signal.release()
        logMessage(LogLevel.MESSAGES) {
            "[${env.nodeId}]: Acquire lock"
        }
    }

    @Operation
    suspend fun lock(): Int {
        logMessage(LogLevel.ALL_EVENTS) {
            "[${env.nodeId}]: In lock"
        }
        check(req[env.nodeId] == inf) {
            Thread.currentThread()
        }
        val myReqTime = ++clock
        req[env.nodeId] = myReqTime
        env.broadcast(Req(++clock, myReqTime))
        if (env.numberOfNodes == 1) {
            inCS = true
        }
        signal.acquire()
        val res = ++counter
        unlock()
        return res
    }

    private suspend fun unlock() {
        if (!inCS) return
        inCS = false
        req[env.nodeId] = inf
        env.broadcast(Rel(++clock))
    }
}

class Counter {
    var cnt = 0
    suspend fun lock(): Int {
        return ++cnt
    }
}

class LamportMutexTest {
    @Test
    fun testSimple() {
        LinChecker.check(
            LamportMutex::class
                .java, DistributedOptions<MutexMessage, Unit>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(Counter::class.java).threads
                (3).messageOrder(MessageOrder.FIFO).actorsPerThread(2)
                .invocationsPerIteration(30).iterations(1000)
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun testNoFifo() {
        LinChecker.check(
            LamportMutex::class
                .java, DistributedOptions<MutexMessage, Unit>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(Counter::class.java).threads
                (3).messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(100).iterations(1000)
        )
    }
}

