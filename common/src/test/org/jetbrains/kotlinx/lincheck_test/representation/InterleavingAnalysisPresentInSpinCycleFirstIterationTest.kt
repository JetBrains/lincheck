/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:Suppress("unused")

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Checks that after we found a spin-cycle, then we will consider interleavings with switches
 * inside the first spin-cycle iteration.
 */
class InterleavingAnalysisPresentInSpinCycleFirstIterationTest {

    // Counter that causes spin-lock in spinLock operation
    private val counter = AtomicInteger(0)
    // Trigger to increment and decrement in spin-cycle to check in causeSpinLock operation
    private val shouldAlwaysBeZero = AtomicInteger(0)
    private val illegalInterleavingFound = AtomicBoolean(false)

    @Operation
    fun causeSpinLock() {
        // Cause spin lock in `spinLock` operation
        counter.incrementAndGet()
        // Check that we switched in the middle of the first spin-cycle iteration
        if (shouldAlwaysBeZero.get() != 0) {
            illegalInterleavingFound.set(true)
            error("Illegal interleaving during spin-cycle found")
        }
        counter.decrementAndGet()
    }

    @Operation
    fun spinLock() {
        if (counter.get() != 0) {
            while (counter.get() != 0) {
                shouldAlwaysBeZero.incrementAndGet()
                check(!illegalInterleavingFound.get()) { "Illegal interleaving during spin-cycle found" }
                shouldAlwaysBeZero.decrementAndGet()
            }
        }
    }

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(InterleavingAnalysisPresentInSpinCycleFirstIterationTest::causeSpinLock) }
                thread { actor(InterleavingAnalysisPresentInSpinCycleFirstIterationTest::spinLock) }
            }
        }
        .checkImpl(this::class.java) { failure ->
            failure.checkLincheckOutput("switch_in_the_middle_of_spin_cycle_causes_error")
        }
}
