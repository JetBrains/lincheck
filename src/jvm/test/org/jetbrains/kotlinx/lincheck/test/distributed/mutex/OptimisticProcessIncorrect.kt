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
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import org.jetbrains.kotlinx.lincheck.paramgen.NodeIdGen
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.junit.Test
import kotlin.coroutines.suspendCoroutine


class OptimisticMutexIncorrect(private val env: Environment<MutexMessage>) : Node<MutexMessage> {
    private val requested = BooleanArray(env.nodes)
    private var inCS = false
    private val signal = Signal()

    override fun onMessage(message: MutexMessage, sender: Int) {
        when (message) {
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


    private fun checkCSEnter() {
        if (!requested[env.id] || inCS) return
        for (i in 0 until env.id) if (requested[i]) return // give way for lower numbered
        inCS = true
        signal.signal()
    }

    @Operation(cancellableOnSuspension = false, blocking = false)
    suspend fun lock(@Param(gen = NodeIdGen::class) nodeId: Int) {
        if (requested[env.id]) {
            suspendCoroutine<Unit> { }
        }
        requested[env.id] = true
        env.broadcast(Req(0, 0))
        checkCSEnter()
        if (env.nodes != 1) {
            signal.await()
        } else {
            inCS = true
        }
        check(inCS)
        env.recordInternalEvent(Lock)
    }

    @Operation(cancellableOnSuspension = false)
    fun unlock(@Param(gen = NodeIdGen::class) nodeId: Int) {
        if (!inCS) return
        inCS = false
        requested[env.id] = false
        env.recordInternalEvent(Unlock)
        env.broadcast(Rel(0))
    }
}


class OptimisticMutexIncorrectTest {
    @Test
    fun `incorrect algorithm`() {
        val failure = DistributedOptions<MutexMessage>()
            .addNodes<OptimisticMutexIncorrect>(nodes = 4, minNodes = 2)
            .sequentialSpecification(MutexSpecification::class.java)
            .actorsPerThread(3)
            .invocationsPerIteration(10000)
            .iterations(30)
            .minimizeFailedScenario(false)
            .checkImpl(OptimisticMutexIncorrect::class.java)
        assert(failure is IncorrectResultsFailure)
    }
}
