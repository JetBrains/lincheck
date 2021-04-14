/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import kotlin.test.*

/**
 * This test checks that parameters in generated scenarios are diversified
 */
class ScenarioGenerationParameterDiversityTest : VerifierState() {
    fun foo(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int) {
        check(setOf(a, b, c, d, e, f).size > 1) { "At least 2 parameters should be different w.h.p." }
    }

    fun bar(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int) {
        check(setOf(a, b, c, d, e, f).size > 1) { "At least 2 parameters should be different w.h.p." }
    }

    @Test
    fun test() {
        // TODO maybe create API like DefaultGens.getDefaultIntGen or something
        val defaultGen = IntGen("")
        val paramGen = IntGen("")
        LincheckStressConfiguration<ScenarioGenerationParameterDiversityTest>().apply {
            invocationsPerIteration(1)
            iterations(100)
            threads(1)

            initialState { ScenarioGenerationParameterDiversityTest() }

            operation(listOf(defaultGen, defaultGen, defaultGen, defaultGen, defaultGen, defaultGen),
                { arg -> foo(arg[0] as Int, arg[1] as Int, arg[2] as Int, arg[3] as Int, arg[4] as Int, arg[5] as Int) })
            operation(listOf(paramGen, paramGen, paramGen, paramGen, paramGen, paramGen),
                { arg -> bar(arg[0] as Int, arg[1] as Int, arg[2] as Int, arg[3] as Int, arg[4] as Int, arg[5] as Int) })
        }.runTest()
    }

    override fun extractState(): Any = 0 // constant state
}
