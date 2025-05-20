/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.lincheck.datastructures.Param
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

    @Suppress("UNUSED_PARAMETER")
    fun resumeOp(mode: Int): Int {
        val cont = cont ?: return -1
        cont.resume(Unit)
        return 0
    }
}
