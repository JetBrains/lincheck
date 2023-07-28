/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SpinCycleWithPeriodTwiceBiggerBySwitchingParametersTest {

    private val counter = AtomicInteger(0)
    private val sharedData = AtomicInteger(0)

    @Operation
    fun spinLockCause() {
        counter.incrementAndGet()
        counter.decrementAndGet()
    }

    @Operation
    fun spinLock() {
        if (counter.get() != 0) {
            spinLockActions()
        }
    }

    private fun spinLockActions() {
        while (true) {
            repeat(2) { proposal ->
                propose(proposal)
            }
        }
    }

    private fun propose(value: Int) {
        sharedData.compareAndSet(1, value)
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(SpinCycleWithPeriodTwiceBiggerBySwitchingParametersTest::spinLockCause) }
                thread { actor(SpinCycleWithPeriodTwiceBiggerBySwitchingParametersTest::spinLock) }
            }
        }
        .minimizeFailedScenario(false)
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_cycle_twice_bigger_because_of_switching_parameters.txt")

}

class SpinCycleWithPeriodTwiceBiggerBySwitchingReceiversTest {

    private val counter = AtomicInteger(0)
    private val sharedData = AtomicInteger(0)

    @Operation
    fun spinLockCause() {
        counter.incrementAndGet()
        counter.decrementAndGet()
    }

    @Operation
    fun spinLock() {
        if (counter.get() != 0) {
            spinLockActions()
        }
    }

    private fun spinLockActions() {
        val descriptors = Array(2) { Descriptor() }
        while (true) {
            repeat(2) {
                descriptors[it].check()
            }
        }
    }

    inner class Descriptor {
        fun check() {
            sharedData.compareAndSet(1, 2)
        }
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(SpinCycleWithPeriodTwiceBiggerBySwitchingReceiversTest::spinLockCause) }
                thread { actor(SpinCycleWithPeriodTwiceBiggerBySwitchingReceiversTest::spinLock) }
            }
        }
        .minimizeFailedScenario(false)
        .checkImpl(this::class.java)
        .checkLincheckOutput("spin_cycle_twice_bigger_because_of_switching_receivers.txt")

}