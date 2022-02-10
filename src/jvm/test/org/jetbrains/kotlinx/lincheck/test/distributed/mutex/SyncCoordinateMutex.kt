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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.NodeEnvironment
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder.ASYNCHRONOUS
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import org.jetbrains.kotlinx.lincheck.paramgen.NodeIdGen
import org.junit.Test
import java.util.*
import kotlin.coroutines.suspendCoroutine


class SyncCoordinateMutex(private val env: NodeEnvironment<MutexMessage>) : Node<MutexMessage> {
    private val coordinatorId = 0
    private val isCoordinator = env.id == coordinatorId
    private val queue = ArrayDeque<Int>()
    private var inCS = -1
    private val condition = Signal()

    override fun onMessage(message: MutexMessage, sender: Int) {
        when (message) {
            is Req -> {
                check(isCoordinator)
                queue.addLast(sender)
                checkCSEnter()
            }
            is Ok -> {
                check(!isCoordinator)
                inCS = env.id
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

    private fun checkCSEnter() {
        if (inCS != -1) return
        val id = queue.pollFirst() ?: return
        inCS = id
        if (id != coordinatorId) {
            env.send(Ok(0), id)
        } else {
            condition.signal()
        }
    }

    @Operation(cancellableOnSuspension = false, blocking = true)
    suspend fun lock(@Param(gen = NodeIdGen::class) nodeId: Int) {
        if (inCS == env.id) {
            suspendCoroutine<Unit> { }
        }
        if (isCoordinator) {
            queue.add(coordinatorId)
            checkCSEnter()
        } else {
            env.send(Req(0, 0), coordinatorId)
        }
        if (inCS != env.id) {
            condition.await()
        }
    }

    @Operation
    fun unlock(@Param(gen = NodeIdGen::class) nodeId: Int) {
        if (inCS != env.id) return
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
        DistributedOptions<MutexMessage>()
            .addNodes<SyncCoordinateMutex>(nodes = 3, minNodes = 2)
            .invocationsPerIteration(30_000)
            .iterations(10)
            .actorsPerThread(3)
            .sequentialSpecification(MutexSpecification::class.java)

    @Test
    fun `algorithm with FIFO`() = commonOptions().check(SyncCoordinateMutex::class.java)

    @Test
    fun `algorithm without FIFO`() = commonOptions().messageOrder(ASYNCHRONOUS).check(SyncCoordinateMutex::class.java)
}