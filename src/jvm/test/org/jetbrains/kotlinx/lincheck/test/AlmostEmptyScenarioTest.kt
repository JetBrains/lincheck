/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ThreadLocalRandom

@Suppress("DEPRECATION_ERROR")
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
