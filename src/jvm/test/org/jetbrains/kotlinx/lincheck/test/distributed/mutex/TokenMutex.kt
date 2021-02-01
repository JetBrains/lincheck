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
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.junit.Test
import java.util.concurrent.locks.ReentrantLock

@Volatile
var tokenCounter = 0

class TokenMutex(private val env: Environment<Int>) : Node<Int> {
    private var status = Status.WAIT
    private var token = 0
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    private val nextId = (env.nodeId + 1) % env.numberOfNodes

    init {
        if (env.nodeId == 0) {
            env.send(0, nextId)
        }
    }

    override fun onMessage(message: Int, sender: Int) {
        lock.withLock {
            token = message
            if (status == Status.REQ) {
                status = Status.CS
                condition.signal()
            } else {
                env.send(token + 1, nextId)
            }
        }
    }

    @Operation
    fun lock() : Int {
        lock.withLock {
            check(status == Status.WAIT)
            status = Status.REQ
            while(status != Status.CS) {
                condition.await()
            }
        }
        val res = ++tokenCounter
        unlock()
        return res
    }

    @Validate
    fun validate() {
        tokenCounter = 0
    }

    private fun unlock() {
        lock.withLock {
            check(status == Status.CS)
            status = Status.WAIT
            env.send(token + 1, nextId)
        }
    }

    enum class Status { WAIT, REQ, CS }
}


class  TokenMutexTest {
    @Test
    fun testSimple() {
        LinChecker.check(TokenMutex::class
                .java, DistributedOptions<MutexMessage, Unit>().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(Counter::class.java).threads
        (2).messageOrder(MessageOrder.SYNCHRONOUS)
                .invocationsPerIteration(100).iterations(1000))
    }

    @Test
    fun testNoFifo() {
        LinChecker.check(TokenMutex::class.java, DistributedOptions<MutexMessage, Unit>().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(Counter::class.java).threads
        (5).messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(100).iterations(1000))
    }
}