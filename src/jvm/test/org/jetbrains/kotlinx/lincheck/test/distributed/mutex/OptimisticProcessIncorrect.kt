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
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.createDistributedOptions
import org.jetbrains.kotlinx.lincheck.strategy.ValidationFailure
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test
import kotlin.coroutines.suspendCoroutine


class OptimisticMutexIncorrect(private val env: Environment<MutexMessage, Unit>) : MutexNode<MutexMessage>() {
    companion object {
        @Volatile
        private var optimisticIncorrect = 0
    }

    private val requested = BooleanArray(env.nodes)
    private var inCS = false
    private val semaphore = Semaphore(1, 1)

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
        if (!requested[env.nodeId] || inCS) return
        for (i in 0 until env.nodeId) if (requested[i]) return // give way for lower numbered
        inCS = true
        semaphore.release()
    }

    @Operation(cancellableOnSuspension = false, blocking = false)
    suspend fun lock() {
        if (requested[env.nodeId]) {
            suspendCoroutine<Unit> { }
        }
        requested[env.nodeId] = true
        env.broadcast(Req(0, 0))
        checkCSEnter()
        if (env.nodes != 1) {
            semaphore.acquire()
        } else {
            inCS = true
        }
        check(inCS)
        env.recordInternalEvent(Lock)
    }

    @Operation(cancellableOnSuspension = false)
    fun unlock() {
        if (!inCS) return
        inCS = false
        requested[env.nodeId] = false
        env.recordInternalEvent(Unlock)
        env.broadcast(Rel(0))
    }
}


class OptimisticMutexIncorrectTest {
    @Test
    fun `incorrect algorithm`() {
        val failure = createDistributedOptions<MutexMessage>()
            .addNodes<OptimisticMutexIncorrect>(nodes = 4, minNodes = 2)
            .verifier(EpsilonVerifier::class.java)
            .actorsPerThread(3)
            .invocationsPerIteration(10000)
            .iterations(30)
            .minimizeFailedScenario(false)
            .checkImpl(OptimisticMutexIncorrect::class.java)
        assert(failure is ValidationFailure)
    }
}
