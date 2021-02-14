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
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder
import org.jetbrains.kotlinx.lincheck.distributed.Node
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

class Lock(msgTime: Int) : MutexMessage(msgTime)
class Unlock(msgTime: Int) : MutexMessage(msgTime)

@Volatile
internal var counter = 0

class LamportMutex(private val env: Environment<MutexMessage, Unit>) : Node<MutexMessage> {
    private val inf = Int.MAX_VALUE
    private var clock = 0 // logical time
    private var inCS = false // are we in critical section?
    private val req = IntArray(env.numberOfNodes) { inf } // time of last REQ message
    private val ok = IntArray(env.numberOfNodes) // time of last OK message
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    override fun onMessage(message: MutexMessage, sender: Int) {
        lock.withLock {
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
       // println("[${env.nodeId}]: In check CS enter, myReq=$myReqTime, req=${req.toList()}, ok=${ok.toList()}")
        for (i in 0 until env.numberOfNodes) {
            if (i == env.nodeId) {
                continue
            }
            if (req[i] < myReqTime || req[i] == myReqTime && i < env.nodeId) {
               // println("[${env.nodeId}]: Condition not met $i (req[i] < myReqTime)=${req[i] < myReqTime} || (req[i] == myReqTime && i < env.nodeId)=${req[i] == myReqTime && i < env.nodeId}")
                return
            }
            if (ok[i] <= myReqTime) {
              //  println("[${env.nodeId}]: Ok condition not met, $i, ${ok[i]}, $myReqTime")
                return
            }
        }
        //println("[${env.nodeId}]: Acquire lock")
        inCS = true
        condition.signal()
        //  env.sendLocal(Lock(clock))
    }

    @Operation
    fun lock(): Int {
       // println("[${env.nodeId}]: request lock")
        lock.withLock {
            check(req[env.nodeId] == inf) {
                Thread.currentThread()
            }
            val myReqTime = ++clock
            req[env.nodeId] = myReqTime
            broadcast(Req(++clock, myReqTime))
            if (env.numberOfNodes == 1) {
                inCS = true
            }
            while (!inCS) {
                condition.await()
            }
        }
        val res = ++counter
        unlock()
        //println("[${env.nodeId}]: unlock")
        return res
    }

    private fun unlock() {
        lock.withLock {
            if (!inCS) return
            //env.sendLocal(Unlock(clock))
            inCS = false
            req[env.nodeId] = inf
            broadcast(Rel(++clock))
        }
    }

    private fun broadcast(msg: MutexMessage) {
        for (i in 0 until env.numberOfNodes) {
            if (i == env.nodeId) {
                continue
            }
            env.send(msg, i)
        }
    }
}

class Counter {
    var cnt = 0
    fun lock(): Int {
        return ++cnt
    }
}

class LamportMutexTest {
    @Test
    fun testSimple() {
        LinChecker.check(LamportMutex::class
                .java, DistributedOptions<MutexMessage, Unit>().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(Counter::class.java).threads
        (5).messageOrder(MessageOrder.FIFO)
                .invocationsPerIteration(100).iterations(1000))
    }

    @Test(expected = LincheckAssertionError::class)
    fun testNoFifo() {
        LinChecker.check(LamportMutex::class
                .java, DistributedOptions<MutexMessage, Unit>().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(Counter::class.java).threads
        (3).messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(100).iterations(1000))
    }
}
*/
