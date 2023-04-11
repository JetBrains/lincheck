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

package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

/**
 * This test checks that parameters in generated scenarios are diversified
 */
@Param(name = "value", gen = IntGen::class)
class ScenarioGenerationParameterDiversityTest : VerifierState() {
    @Operation
    fun foo(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int) {
        check(setOf(a, b, c, d, e, f).size > 1) { "At least 2 parameters should be different w.h.p."}
    }

    @Operation(params = ["value", "value", "value", "value", "value", "value"])
    fun bar(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int) {
        check(setOf(a, b, c, d, e, f).size > 1) { "At least 2 parameters should be different w.h.p."}
    }

    @Test
    fun test() = LincheckOptions {
        testingTimeInSeconds = 1
    }.check(this::class.java)

    override fun extractState(): Any = 0 // constant state
}
