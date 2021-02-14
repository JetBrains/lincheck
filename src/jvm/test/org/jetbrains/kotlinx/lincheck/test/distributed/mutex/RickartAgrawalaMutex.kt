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
/*
package org.jetbrains.kotlinx.lincheck.test.distributed.mutex

import kotlinx.atomicfu.locks.withLock
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.junit.Test
import java.util.concurrent.locks.ReentrantLock

@Volatile
internal var cnt = 0

class RickartAgrawalaMutex(private val env : Environment<MutexMessage, Unit>) : Node<MutexMessage> {
    private val inf = Int.MAX_VALUE
    private var clock = 0 // logical time
    private var inCS = false // are we in critical section?
    private val req = IntArray(env.numberOfNodes) { inf } // time of last REQ message
    private val ok = IntArray(env.numberOfNodes) // time of last OK message
    private val pendingOk = BooleanArray(env.numberOfNodes)
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    override fun onMessage(message: MutexMessage, sender : Int) {
        lock.withLock {
            val time = message.msgTime
            clock = Integer.max(clock, time) + 1
            when(message) {
                is Req -> {
                    val reqTime = message.reqTime
                    req[sender] = reqTime
                    val myReqTime = req[env.nodeId]
                    //println("[${env.nodeId}]: myReqTime $myReqTime reqTime $reqTime sender $sender")
                    if (reqTime < myReqTime || reqTime == myReqTime &&  sender < env.nodeId) {
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
    }

    @Validate
    fun validate() {
        cnt = 0
    }

    private fun checkInCS()  {
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
        //println("[${env.nodeId}]: Acquire lock")
        condition.signal()
        //  env.sendLocal(Lock(clock))
    }

    @Operation
    fun lock() : Int {
        //println("[${env.nodeId}]: Request lock")
        lock.withLock {
            check (req[env.nodeId] == inf) {
                Thread.currentThread()
            }
            val myReqTime = ++clock
            req[env.nodeId] = myReqTime
            broadcast(Req(++clock, myReqTime))
            if (env.numberOfNodes == 1) {
                inCS = true
            }
            while(!inCS) {
                condition.await()
            }
        }
        val res = ++cnt
        unlock()
        return res
    }

    private fun unlock() {
       // println("[${env.nodeId}]: Unlock")
        lock.withLock {
            if (!inCS) return
            //env.sendLocal(Unlock(clock))
            inCS = false
            req[env.nodeId] = inf
            for (i in 0 until env.numberOfNodes) {
                if (pendingOk[i]) {
                    pendingOk[i] = false
                    env.send(Ok(++clock), i)
                }
            }
        }
    }

    private fun broadcast(msg : MutexMessage) {
        for (i in 0 until env.numberOfNodes) {
            if (i == env.nodeId) {
                continue
            }
            env.send(msg, i)
        }
    }
}

class  RickartAgrawalaMutexTest {
    @Test
    fun testSimple() {
        LinChecker.check(RickartAgrawalaMutex::class
                .java, DistributedOptions<MutexMessage, Unit>().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(Counter::class.java).threads
        (5).messageOrder(MessageOrder.SYNCHRONOUS)
                .invocationsPerIteration(100).iterations(1000))
    }

    @Test
    fun testNoFifo() {
        LinChecker.check(RickartAgrawalaMutex::class.java, DistributedOptions<MutexMessage, Unit>().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(Counter::class.java).threads
        (5).messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(100).iterations(1000))
    }
}*/