/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.forClasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Checks that the bug, which can only be found with a proper multiple suspension point support, is found
 * and correctly reported. See expected output for more details.
 */
@Suppress("RemoveExplicitTypeArguments")
class MultipleSuspensionTest {

    private var counter = AtomicInteger(0)
    private var continuation1: Continuation<Unit>? = null
    private var continuation2: Continuation<Unit>? = null

    @Operation
    fun trigger() {
        firstStep()
        secondStep()
        counter.set(3)
    }

    private fun firstStep() {
        counter.set(1)
        continuation1?.resume(Unit)
    }

    private fun secondStep() {
        counter.set(2)
        continuation2?.resume(Unit)
    }

    @Operation
    suspend fun operation(): Int {
        if (part1()) return 0
        if (part2()) return 0
        counter.get()
        return 1
    }

    private suspend fun part2(): Boolean {
        if (counter.get() == 1) {
            suspendCoroutine<Unit> { continuation2 = it }
        } else return true
        return false
    }

    private suspend fun part1(): Boolean {
        if (counter.get() == 0) {
            suspendCoroutine<Unit> { continuation1 = it }
        } else return true
        return false
    }

    @Test
    fun test(): Unit = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::trigger) }
                thread { actor(::operation) }
            }
        }
        .addGuarantee(forClasses(this::class.java.name).methods("firstStep", "secondStep", "part1", "part2").treatAsAtomic())
        .checkImpl(this::class.java)
        .checkLincheckOutput("two_suspension_points_bug.txt")

}

/**
 * Check the proper output in case when one actor cause incorrect behaviour only after coroutine resumption.
 */
class SingleSuspensionTraceReportingTest {

    private var counter = AtomicInteger(0)
    private var continuation1: Continuation<Unit>? = null
    @Volatile
    private var fail: Boolean = false

    @Operation
    fun triggerAndCheck(): Boolean {
        return continueAndCheckIfFailed()
    }

    private fun continueAndCheckIfFailed(): Boolean {
        counter.set(1)
        continuation1?.resume(Unit)
        return fail
    }

    @Operation
    suspend fun operation(): Int {
        // Nested method to check proper trace reporting
        return suspendAndCauseFailure()
    }

    @Suppress("SameReturnValue")
    private suspend fun suspendAndCauseFailure(): Int {
        if (counter.get() == 1) {
            @Suppress("RemoveExplicitTypeArguments")
            // Doesn't compile without explicit typing for now
            suspendCoroutine<Unit> { continuation1 = it }
        } else return 0
        fail = true
        return 0
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::triggerAndCheck) }
                thread { actor(::operation) }
            }
        }
        .checkImpl(this::class.java)
        .checkLincheckOutput("single_suspension_trace.txt")

}

/**
 * Checks the data structure which actors may suspend multiple times.
 * Passes only if the multiple suspension points are supported properly.
 */
@OptIn(InternalCoroutinesApi::class)
class CoroutinesMultipleSuspensionsTest : AbstractLincheckTest() {
    private val locked = atomic(false)
    private val waiters = ConcurrentLinkedQueue<CancellableContinuation<Unit>>()

    @Operation
    suspend fun lock() {
        while (true) {
            if (locked.compareAndSet(false, true)) return
            suspendCancellableCoroutine { cont ->
                waiters.add(cont)
                if (!locked.value) {
                    if (waiters.remove(cont)) cont.resume(Unit)
                }
            }
        }
    }

    @Operation
    fun unlock() {
        if (!locked.compareAndSet(true, false)) error("mutex was not locked")
        while (true) {
            val w = waiters.poll() ?: break
            val token = w.tryResume(Unit, null) ?: continue
            w.completeResume(token)
            return
        }
    }
}