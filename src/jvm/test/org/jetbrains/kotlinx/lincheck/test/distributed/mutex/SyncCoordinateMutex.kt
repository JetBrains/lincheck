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
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock

@Volatile
var syncCounter = 0

class SyncCoordinateMutex(private val env: Environment<MutexMessage>) : Node<MutexMessage> {
    private val coordinatorId = 0
    private val isCoordinator = env.nodeId == coordinatorId
    private val queue = ArrayDeque<Int>()
    private var inCS = -1
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    override fun onMessage(message: MutexMessage, sender: Int) {
        lock.withLock {
            when (message) {
                is Req -> {
                    check(isCoordinator)
                    queue.addLast(sender)
                    checkCSEnter()
                }
                is Ok -> {
                    check(!isCoordinator)
                    inCS = env.nodeId
                    condition.signal()
                }
                is Rel -> {
                    check(isCoordinator)
                    check(inCS == sender)
                    inCS = -1
                    checkCSEnter()
                }
            }
        }
    }

    private fun checkCSEnter() {
        if (inCS != -1) return
        val id = queue.pollFirst() ?: return
        //println("Id $id")
        inCS = id
        if (id != coordinatorId) {
            env.send(Ok(0), id)
        } else {
            condition.signal()
        }
    }

    @Validate
    fun validate() {
        syncCounter = 0
    }

    @Operation
    fun lock(): Int {
        //println("[${env.nodeId}]: Request lock")
        lock.withLock {
            if (isCoordinator) {
                queue.add(coordinatorId)
                checkCSEnter()
            } else {
                env.send(Req(0, 0), coordinatorId)
            }
            while (inCS != env.nodeId) {
                condition.await()
            }
        }
        //println("[${env.nodeId}]: Acquire lock")
        val res = ++syncCounter
        unlock()
        return res
    }

    private fun unlock() {
        //println("[${env.nodeId}]: Release lock")
        lock.withLock {
            check(inCS == env.nodeId)
            inCS = -1
            if (isCoordinator) {
                checkCSEnter()
            } else {
                env.send(Rel(0), coordinatorId)
            }
        }
    }
}

class  SyncCoordinateMutexTest {
    @Test
    fun testSimple() {
        LinChecker.check(SyncCoordinateMutex::class
                .java, DistributedOptions<MutexMessage>().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(Counter::class.java).threads
        (2).messageOrder(MessageOrder.SYNCHRONOUS)
                .invocationsPerIteration(100).iterations(1000))
    }

    @Test
    fun testNoFifo() {
        LinChecker.check(SyncCoordinateMutex::class.java, DistributedOptions<MutexMessage>().requireStateEquivalenceImplCheck
        (false).sequentialSpecification(Counter::class.java).threads
        (5).messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(100).iterations(1000))
    }
}