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

package org.jetbrains.kotlinx.lincheck.test

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import kotlin.coroutines.*
import kotlin.reflect.*

abstract class AbstractPromptCancellationTest(
    vararg expectedFailures: KClass<out LincheckFailure>,
    val sequentialSpecification: KClass<*>? = null
) : AbstractLincheckTest(*expectedFailures) {
    @Volatile
    private var returnResult = 0

    @Volatile
    private var cont: CancellableContinuation<Unit>? = null

    private val completedOrCancelled = atomic(false)

    @Operation(promptCancellation = true, runOnce = true)
    suspend fun suspendOp() {
        suspendCancellableCoroutine<Unit> { cont ->
            this.cont = cont
        }
        check(completedOrCancelled.compareAndSet(false, true))
    }

    @ExperimentalCoroutinesApi
    @InternalCoroutinesApi
    @Operation(runOnce = true)
    fun resumeOp(@Param(gen = IntGen::class, conf = "1:2") mode: Int): Int {
        val cont = cont ?: return -1
        when (mode) {
            1 -> { // resume
                cont.resume(Unit) {
                    check(completedOrCancelled.compareAndSet(false, true))
                    returnResult = 42
                }
            }
            2 -> { // tryResume
                val token = cont.tryResume(Unit, null) {
                    check(completedOrCancelled.compareAndSet(false, true))
                    returnResult = 42
                }
                if (token != null) cont.completeResume(token)
            }
            else -> error("Unexpected")
        }
        return returnResult
    }

    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(0)
        threads(2)
        actorsPerThread(1)
        actorsAfter(0)
        sequentialSpecification?.let { sequentialSpecification(it.java) }
    }
}

class CorrectPromptCancellationTest : AbstractPromptCancellationTest()

class IncorrectPromptCancellationTest : AbstractPromptCancellationTest(
    IncorrectResultsFailure::class,
    sequentialSpecification = IncorrectPromptCancellationSequential::class
)

class IncorrectPromptCancellationSequential {
    var cont: CancellableContinuation<Unit>? = null

    suspend fun suspendOp() {
        suspendCancellableCoroutine<Unit> { cont ->
            this.cont = cont
        }
    }

    fun resumeOp(mode: Int): Int {
        val cont = cont ?: return -1
        cont.resume(Unit)
        return 0
    }
}
