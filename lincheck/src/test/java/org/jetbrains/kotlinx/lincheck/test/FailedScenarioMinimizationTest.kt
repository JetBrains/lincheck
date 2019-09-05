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
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Assert.*
import org.junit.Test
import java.lang.AssertionError

@Param(name = "key", gen = IntGen::class, conf = "1:5")
class FailedScenarioMinimizationTest: VerifierState() {
    private val m = HashMap<Int, Int>()
    override fun extractState() = m

    @Operation
    fun put(@Param(name = "key") key: Int, @Param(gen = IntGen::class) value: Int) = m.put(key, value)

    @Operation
    fun get(@Param(name = "key") key: Int) = m.get(key)

    @Operation
    fun remove(@Param(name = "key") key: Int) = m.remove(key)

    @Test
    fun testWithoutMinimization() {
        val options = StressOptions().actorsPerThread(15).minimizeFailedScenario(false)
        try {
            LinChecker.check(FailedScenarioMinimizationTest::class.java, options)
            fail("Should fail with AssertionError")
        } catch (e: AssertionError) {
            val m = e.message!!
            assertTrue("The init part should NOT be minimized", m.contains("Init"))
            assertTrue("The post part should NOT be minimized", m.contains("Post"))
            assertEquals("The parallel part should NOT be minimized",
                    15, m.lines().filter { it.contains("|") }.size)
        }
    }

    @Test
    fun testWithMinimization() {
        val options = StressOptions().actorsPerThread(15) // minimizeFailedScenario == true by default
        try {
            LinChecker.check(FailedScenarioMinimizationTest::class.java, options)
            fail("Should fail with AssertionError")
        } catch (e: AssertionError) {
            val m = e.message!!
            assertFalse("The init part should be minimized", m.contains("Init"))
            assertFalse("The post part should be minimized", m.contains("Post"))
            assertEquals("The error should be reproduced with one operation per thread",
                    1, m.lines().filter { it.contains("|") }.size)
        }
    }
}
