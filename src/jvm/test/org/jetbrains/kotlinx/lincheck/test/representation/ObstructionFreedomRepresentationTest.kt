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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.test.*
import org.jetbrains.kotlinx.lincheck.test.util.runModelCheckingTestAndCheckOutput
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import java.lang.StringBuilder
import java.util.concurrent.atomic.*

/**
 * This test checks that the last event in the case of an active lock
 * that causes obstruction freedom violation is reported.
 */
class ObstructionFreedomActiveLockRepresentationTest : VerifierState() {
    private val counter = AtomicInteger(0)

    @Operation
    fun operation() {
        // The first invocation will not fail
        incrementManyTimes()
        // This invocation will fail immediately after `counter.get`,
        // but obstruction freedom violation will be caused by `counter.incrementAndGet`
        incrementManyTimes()
    }

    fun incrementManyTimes() {
        counter.get()
        // repeat exactly the maximum number of times that does not cause obstruction freedom violation
        repeat(ManagedCTestConfiguration.DEFAULT_HANGING_DETECTION_THRESHOLD) {
            counter.incrementAndGet()
        }
    }

    override fun extractState(): Any = counter.get()

    @Test
    fun test() {
        val options = ModelCheckingOptions()
            .actorsPerThread(1)
            .actorsBefore(0)
            .actorsAfter(0)
            .threads(1)
            .checkObstructionFreedom(true)
        val failure = options.checkImpl(this::class.java)
        check(failure != null) { "the test should fail" }
        val log = StringBuilder().appendFailure(failure).toString()
        check("incrementAndGet" in log) { "The cause of the error should be reported" }
        checkTraceHasNoLincheckEvents(log)
    }
}

/**
 * This test checks that the last MONITORENTER event
 * that causes obstruction freedom violation is reported.
 */
class ObstructionFreedomSynchronizedRepresentationTest : VerifierState() {
    private var counter = 0

    @Operation
    fun operation(): Int = synchronized(this) { counter++ }

    override fun extractState(): Any = counter

    @Test
    fun test() = runModelCheckingTestAndCheckOutput("obstruction_freedom_synchronized.txt") {
        actorsPerThread(1)
        actorsBefore(0)
        actorsAfter(0)
        threads(2)
        checkObstructionFreedom(true)
    }

}
