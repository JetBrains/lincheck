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
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.junit.Test
import java.util.concurrent.locks.ReentrantLock

@Volatile
internal var optimisticIncorrect = 0

class OptimisticMutexIncorrect(private val env : Environment<MutexMessage, Unit>) : Node<MutexMessage> {
    private val requested = BooleanArray(env.numberOfNodes)
    private var inCS = false
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    override fun onMessage(message: MutexMessage, sender : Int) {
        lock.withLock {
            when(message) {
                is Req -> {
                    requested[sender] = true
                }
                is Rel -> {
                    requested[sender] = false
                }
                else -> throw RuntimeException("Unexpected message type")
            }
            checkCSEnter()
        }
    }

    @Validate
    fun validate() {
        optimisticIncorrect = 0
    }

    private fun checkCSEnter() {
        if (!requested[env.nodeId] || inCS) return
        for (i in 0 until env.nodeId) if (requested[i]) return // give way for lower numbered
        inCS = true
        condition.signal()
    }

    @Operation
    fun lock() : Int {
        // println("[${env.nodeId}]: request lock")
        lock.withLock {
            check(!requested[env.nodeId])
            requested[env.nodeId] = true
            broadcast(Req(0, 0))
            checkCSEnter()
            while(!inCS) {
                condition.await()
            }
        }
        val res = ++optimisticIncorrect
        unlock()
        //println("[${env.nodeId}]: unlock")
        return res
    }

    private fun unlock() {
        lock.withLock {
            check(inCS)
            inCS = false
            requested[env.nodeId] = false
            broadcast(Rel(0))
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


class OptimisticMutexIncorrectTest {
    @Test(expected = LincheckAssertionError::class)
    fun testSimple() {
        LinChecker.check(OptimisticMutexIncorrect::class
                .java, DistributedOptions<MutexMessage, Unit>().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(Counter::class.java).threads
        (5).messageOrder(MessageOrder.SYNCHRONOUS)
                .invocationsPerIteration(100).iterations(1000))
    }
}