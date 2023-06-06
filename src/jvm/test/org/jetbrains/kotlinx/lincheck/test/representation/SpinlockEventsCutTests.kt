@file:Suppress("UNUSED")
/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.test.representation

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.test.guide.MSQueueBlocking
import org.jetbrains.kotlinx.lincheck.test.util.runModelCheckingTestAndCheckOutput
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ObstructionFreedomViolationEventsCutTest {
    private val q = MSQueueBlocking()

    @Operation
    fun enqueue(x: Int) = q.enqueue(x)

    @Operation
    fun dequeue(): Int? = q.dequeue()

    @Test
    fun runModelCheckingTest() = runModelCheckingTestAndCheckOutput("obstruction_freedom_violation_events_cut.txt") {
        checkObstructionFreedom(true)
    }
}

class SpinlockEventsCutShortLengthTest : AbstractSpinLivelockTest() {

    private val sharedStateAny = AtomicBoolean(false)

    override val outputFileName: String get() = "spin_lock_events_cut.txt"

    override fun meaninglessActions() {
        sharedStateAny.get()
    }
}


class SpinlockEventsCutMiddleLengthTest : AbstractSpinLivelockTest() {

    private val sharedStateAny = AtomicBoolean(false)

    override val outputFileName: String get() = "spin_lock_events_cut_2.txt"

    override fun meaninglessActions() {
        val x = sharedStateAny.get()
        sharedStateAny.set(!x)
    }
}

class SpinlockEventsCutInfiniteLoopTest : AbstractSpinLivelockTest() {

    private val sharedStateAny = AtomicBoolean(false)

    override val outputFileName: String get() = "infinite_loop_events_cut.txt"

    override fun meaninglessActions() {
        while (true) {
            val x = sharedStateAny.get()
            sharedStateAny.set(!x)
        }
    }
}

abstract class AbstractSpinLivelockTest {
    private val sharedState1 = AtomicBoolean(false)
    private val sharedState2 = AtomicBoolean(false)

    abstract val outputFileName: String

    @Operation
    fun one(): Int {
        while (!sharedState1.compareAndSet(false, true)) {
            meaninglessActions()
        }
        while (!sharedState2.compareAndSet(false, true)) {
            meaninglessActions()
        }
        sharedState1.set(false)
        sharedState2.set(false)

        return 1
    }

    @Operation
    fun two(): Int {
        while (!sharedState2.compareAndSet(false, true)) {
            meaninglessActions()
        }
        while (!sharedState1.compareAndSet(false, true)) {
            meaninglessActions()
        }
        sharedState2.set(false)
        sharedState1.set(false)

        return 2
    }

    abstract fun meaninglessActions()

    @Test
    fun testWithModelCheckingStrategy() = runModelCheckingTestAndCheckOutput(outputFileName) {
        minimizeFailedScenario(false)
    }
}
