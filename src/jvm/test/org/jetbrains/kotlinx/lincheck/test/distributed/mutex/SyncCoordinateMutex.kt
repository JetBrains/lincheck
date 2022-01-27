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
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder.ASYNCHRONOUS
import org.jetbrains.kotlinx.lincheck.distributed.createDistributedOptions
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test
import java.util.*
import kotlin.coroutines.suspendCoroutine


class SyncCoordinateMutex(private val env: Environment<MutexMessage, Unit>) : MutexNode<MutexMessage>() {
    private val coordinatorId = 0
    private val isCoordinator = env.nodeId == coordinatorId
    private val queue = ArrayDeque<Int>()
    private var inCS = -1
    private val condition = Semaphore(1, 1)

    fun signal() {
        if (condition.availablePermits == 0) {
            condition.release()
        }
    }

    override fun onMessage(message: MutexMessage, sender: Int) {
        when (message) {
            is Req -> {
                check(isCoordinator)
                queue.addLast(sender)
                checkCSEnter()
            }
            is Ok -> {
                check(!isCoordinator)
                inCS = env.nodeId
                signal()
            }
            is Rel -> {
                check(isCoordinator)
                check(inCS == sender)
                inCS = -1
                checkCSEnter()
            }
        }
    }

    private fun checkCSEnter() {
        if (inCS != -1) return
        val id = queue.pollFirst() ?: return
        inCS = id
        if (id != coordinatorId) {
            env.send(Ok(0), id)
        } else {
            signal()
        }
    }

    @Operation(cancellableOnSuspension = false, blocking = true)
    suspend fun lock() {
        if (inCS == env.nodeId) {
            suspendCoroutine<Unit> { }
        }
        if (isCoordinator) {
            queue.add(coordinatorId)
            checkCSEnter()
        } else {
            env.send(Req(0, 0), coordinatorId)
        }
        if (inCS != env.nodeId) {
            condition.acquire()
        }
    }

    @Operation
    fun unlock() {
        if (inCS != env.nodeId) return
        inCS = -1
        if (isCoordinator) {
            checkCSEnter()
        } else {
            env.send(Rel(0), coordinatorId)
        }
    }
}

class SyncCoordinateMutexTest {
    private fun commonOptions() =
        createDistributedOptions<MutexMessage>()
            .addNodes<SyncCoordinateMutex>(nodes = 3, minNodes = 2)
            .invocationsPerIteration(30_000)
            .iterations(10)
            .actorsPerThread(3)
            .verifier(EpsilonVerifier::class.java)

    @Test
    fun `algorithm with FIFO`() = commonOptions().check(SyncCoordinateMutex::class.java)

    @Test
    fun `algorithm without FIFO`() = commonOptions().messageOrder(ASYNCHRONOUS).check(SyncCoordinateMutex::class.java)
}