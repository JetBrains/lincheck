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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.util.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.jetbrains.lincheck.datastructures.ManagedCTestConfiguration

import java.util.concurrent.atomic.*
import org.junit.Assume.assumeFalse
import org.junit.*


/**
 * This test checks that the last event in the case of an active lock
 * that causes obstruction freedom violation is reported.
 */
class ObstructionFreedomActiveLockRepresentationTest : BaseTraceRepresentationTest(
    "obstruction_freedom_violation_with_no_detected_cycle"
) {
    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

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
        repeat(ManagedCTestConfiguration.DEFAULT_HANGING_DETECTION_THRESHOLD) {
            counter.incrementAndGet()
        }
    }

    override fun ModelCheckingOptions.customize() {
        checkObstructionFreedom(true)
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
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::operation) }
                thread { actor(::operation) }
            }
        }
        .checkObstructionFreedom(true)
        .checkImpl(this::class.java)
        .checkLincheckOutput("obstruction_freedom_synchronized")

}
