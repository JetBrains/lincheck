/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.representation

import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.LincheckSettings.LoopDetection
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck_test.util.*
import org.jetbrains.lincheck_test.util.detectFailure

import java.util.concurrent.atomic.*
import org.junit.*


/**
 * This test checks that the last event in the case of an active lock
 * that causes obstruction freedom violation is reported.
 */
class ObstructionFreedomActiveLockRepresentationTest : BaseTraceRepresentationTest(
    "obstruction_freedom_violation_with_no_detected_cycle"
) {
    private val counter = AtomicInteger(0)

    override fun operation() {
        // The first invocation will not fail
        incrementManyTimes()
        // This invocation will fail immediately after `counter.get`,
        // but obstruction freedom violation will be caused by `counter.incrementAndGet`
        incrementManyTimes()
    }

    private fun incrementManyTimes() {
        counter.get()
        // repeat exactly the maximum number of times that does not cause obstruction freedom violation
        repeat(LoopDetection.DEFAULT_LOOP_BOUND) {
            counter.incrementAndGet()
        }
    }

    override fun LincheckSettings.customize() {
        modelChecking { checkObstructionFreedom = true }
    }

}

/**
 * This test checks that the last MONITORENTER event
 * that causes obstruction freedom violation is reported.
 */
class ObstructionFreedomSynchronizedRepresentationTest {
    private var counter = 0

    @Operation
    fun operation(): Int = synchronized(this) { counter++ }

    @Test
    fun test() = LincheckSettings {
        scenarioGeneration {
            addCustomScenario {
                parallel {
                    thread { actor(::operation) }
                    thread { actor(::operation) }
                }
            }
        }
        modelChecking { checkObstructionFreedom = true }
    }.detectFailure(this::class.java)
        .checkLincheckOutput("obstruction_freedom_synchronized")

}
