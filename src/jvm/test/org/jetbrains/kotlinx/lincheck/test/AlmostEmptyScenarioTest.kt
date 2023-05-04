/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
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
            fail("Should fail with AssertionError")
        } catch (e: AssertionError) {
            val m = e.message!!
            assertFalse("Empty init/post parts should not be printed", m.contains(Regex("\\\\[\\s*\\\\]")))
            assertFalse("Empty init/post parts should not be printed", m.contains(Regex("Init")))
            assertFalse("Empty init/post parts should not be printed", m.contains(Regex("Post")))
            assertFalse("Empty threads should not be printed", m.contains(Regex("\\|\\s*\\|")))
        }
    }
}
