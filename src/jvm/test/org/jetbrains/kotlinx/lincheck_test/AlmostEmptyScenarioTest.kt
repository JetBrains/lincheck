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

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.junit.jupiter.api.Test
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@StressCTest(iterations = 1, requireStateEquivalenceImplCheck = false, actorsBefore = 1, actorsAfter = 1, threads = 3)
class AlmostEmptyScenarioTest {
    @Operation(runOnce = true)
    fun operation1() = ThreadLocalRandom.current().nextInt(5)

    @Operation(runOnce = true)
    fun operation2() = ThreadLocalRandom.current().nextInt(5)

    @Test
    fun test() {
        try {
            LinChecker.check(AlmostEmptyScenarioTest::class.java)
            fail("Should fail with LincheckAssertionError")
        } catch (e: LincheckAssertionError) {
            val failedScenario = e.failure.scenario
            assertTrue(failedScenario.initExecution.isEmpty(), "The init part should be empty")
            assertTrue(failedScenario.postExecution.isEmpty(), "The post part should be empty")
            assertEquals(1, failedScenario.parallelExecution.size,
                "The error should be reproduced with one thread")
            assertEquals(1, failedScenario.parallelExecution[0].size,
                "The error should be reproduced with one operation")
        }
    }
}
