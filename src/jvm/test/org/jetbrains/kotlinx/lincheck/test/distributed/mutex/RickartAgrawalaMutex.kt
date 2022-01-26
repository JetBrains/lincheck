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

import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test
import kotlin.coroutines.suspendCoroutine

fun IntArray.happensBefore(other: IntArray): Boolean {
    check(size == other.size)
    for (i in indices) {
        if (other[i] < this[i]) return false
    }
    return true
}


class RickartAgrawalaMutex(private val env: Environment<MutexMessage, Unit>) : MutexNode<MutexMessage>() {
    companion object {
        @Volatile
        private var cnt = 0
    }

    private val inf = Int.MAX_VALUE
    private var clock = 0 // logical time
    private var inCS = false // are we in critical section?
    private val req = IntArray(env.numberOfNodes) { inf } // time of last REQ message
    private val ok = IntArray(env.numberOfNodes) // time of last OK message
    private val pendingOk = BooleanArray(env.numberOfNodes)
    private val semaphore = Semaphore(1, 1)

    override fun onMessage(message: MutexMessage, sender: Int) {
        val time = message.msgTime
        clock = Integer.max(clock, time) + 1
        when (message) {
            is Req -> {
                val reqTime = message.reqTime
                req[sender] = reqTime
                val myReqTime = req[env.nodeId]
                //println("[${env.nodeId}]: myReqTime $myReqTime reqTime $reqTime sender $sender")
                if (reqTime < myReqTime || reqTime == myReqTime && sender < env.nodeId) {
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
        val myReqTime = req[env.nodeId]
        if (myReqTime == inf) return // did not request CS, do nothing
        if (inCS) return // already in CS, do nothing
        for (i in 0 until env.numberOfNodes) {
            if (i != env.nodeId) {
                if (req[i] < myReqTime || req[i] == myReqTime && i < env.nodeId) return // better ticket
                if (ok[i] <= myReqTime) return // did not Ok our request
            }
        }
        inCS = true
        semaphore.release()
    }

    @Operation(cancellableOnSuspension = false, blocking = true)
    suspend fun lock() {
        //println("[${env.nodeId}]: Request lock")
        if (inCS) {
            suspendCoroutine<Unit> { }
        }
        val myReqTime = ++clock
        req[env.nodeId] = myReqTime
        env.broadcast(Req(++clock, myReqTime))
        if (env.numberOfNodes == 1) {
            inCS = true
        } else {
            semaphore.acquire()
            check(inCS)
        }
        env.recordInternalEvent("Lock")
    }

    @Operation(cancellableOnSuspension = false)
    fun unlock() {
        if (!inCS) return
        inCS = false
        req[env.nodeId] = inf
        env.recordInternalEvent("Unlock")
        for (i in 0 until env.numberOfNodes) {
            if (pendingOk[i]) {
                pendingOk[i] = false
                env.send(Ok(++clock), i)
            }
        }
    }
}

class RickartAgrawalaMutexTest {
    @Test
    fun testSimple() {
        LinChecker.check(
            RickartAgrawalaMutex::class.java,
            createDistributedOptions<MutexMessage>()
                .requireStateEquivalenceImplCheck(false)
                .verifier(EpsilonVerifier::class.java)
                 //.sequentialSpecification(MutexSpecification::class.java)
                .threads(4)
                .messageOrder(MessageOrder.FIFO)
                .actorsPerThread(3)
                .invocationsPerIteration(5_000)
                .iterations(10)
        )
    }

    @Test
    fun testNoFifo() {
        LinChecker.check(
            RickartAgrawalaMutex::class.java,
            createDistributedOptions<MutexMessage>()
                .requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(MutexSpecification::class.java)
                .threads(3)
                .actorsPerThread(3)
                .messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(3000)
                .iterations(10)
        )
    }
}