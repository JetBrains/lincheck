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
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ThreadLocalRandom

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
            assertTrue("The init part should be empty", failedScenario.initExecution.isEmpty())
            assertTrue("The post part should be empty", failedScenario.postExecution.isEmpty())
            assertEquals("The error should be reproduced with one thread",
                1, failedScenario.parallelExecution.size)
            assertEquals("The error should be reproduced with one operation",
                1, failedScenario.parallelExecution[0].size)
        }
    }
}
