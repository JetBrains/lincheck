/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test


class HangingDetectionThresholdTest {
    private var c = AtomicInteger(0)

    private fun get(): Int {
        return c.get()
    }

    @Operation
    fun operation() {
        get()
        get()
    }

    @Test
    fun test() {
        val options = ModelCheckingOptions()
            .checkObstructionFreedom()
            .minimizeFailedScenario(false)
            // we check that no actor can hit the same code location more than twice;
            // because `operation` has only two calls to `get`, it should be true
            .hangingDetectionThreshold(2)
            .addCustomScenario {
                parallel {
                    thread {
                        actor(::operation)
                        actor(::operation)
                    }
                }
            }
            .iterations(0)

        val failure = options.checkImpl(HangingDetectionThresholdTest::class.java)
        check(failure == null)
    }

}