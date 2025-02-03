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

import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.forClasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.util.ensure
import org.jetbrains.kotlinx.lincheck_test.util.isJdk8
import org.junit.Test

/**
 * Check the proper output in case when one actor cause incorrect behavior only after coroutine resumption.
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
 * Checks that the bug, which can only be found with a proper multiple suspension point support,
 * is detected and correctly reported.
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

    private suspend fun part1(): Boolean {
        if (counter.get() == 0) {
            suspendCoroutine<Unit> { continuation1 = it }
        } else return true
        return false
    }

    private suspend fun part2(): Boolean {
        if (counter.get() == 1) {
            suspendCoroutine<Unit> { continuation2 = it }
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
        .addGuarantee(
            forClasses(this::class.java.name).methods("firstStep", "secondStep", "part1", "part2")
                .treatAsAtomic()
        )
        .checkImpl(this::class.java)
        .checkLincheckOutput("two_suspension_points_bug.txt")

}

/**
 * Check the proper output in case when one actor cause incorrect behavior only after coroutine resumption.
 */
@Suppress("RemoveExplicitTypeArguments")
class MultipleSuspensionChannelsTest {

    private var counter: Int = 0
    private val channel1 = Channel<Int>()
    private val channel2 = Channel<Int>()

    @Operation
    suspend fun operation1() {
        for (i in 1 .. 3) {
            channel1.send(i)
            counter++
            channel2.receive().ensure { it == i }
        }
        check(counter == 6)
    }

    @Operation
    suspend fun operation2() {
        for (i in 1 .. 3) {
            channel1.receive().ensure { it == i }
            counter++
            channel2.send(i)
        }
        check(counter == 6)
    }

    @Test
    fun test(): Unit = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::operation1) }
                thread { actor(::operation2) }
            }
        }
        .checkImpl(this::class.java) { failure ->
            failure.checkLincheckOutput(
                when {
                    isInTraceDebuggerMode   -> "multiple_suspension_points_channels_trace_debugger.txt"
                    else                    -> "multiple_suspension_points_channels.txt"
                }
            )
        }
}