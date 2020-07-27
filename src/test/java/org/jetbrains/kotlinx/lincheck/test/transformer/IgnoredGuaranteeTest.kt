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
package org.jetbrains.kotlinx.lincheck.test.transformer

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.forClasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

/**
 * This test checks that managed strategy do not try to switch
 * thread context inside methods marked as ignored.
 * In case the strategy do try, the test will timeout, because
 * the number of invocations is set to Int.MAX_VALUE.
 */
@ModelCheckingCTest(actorsBefore = 0, actorsAfter = 0, actorsPerThread = 50, invocationsPerIteration = Int.MAX_VALUE, iterations = 50)
class IgnoredGuaranteeTest : VerifierState() {
    var value: Int = 0
    val any: Any = this

    @Operation
    fun operation() {
        inc()
    }

    private fun inc() {
        value++
        value++
    }

    @Test(timeout = 100_000)
    fun test() {
        val options = ModelCheckingOptions()
                .actorsBefore(0)
                .actorsAfter(0)
                .actorsPerThread(50)
                .iterations(50)
                .invocationsPerIteration(Int.MAX_VALUE)
                .addGuarantee(forClasses(this::class.java.name).methods("inc").ignore())
        options.check(this::class.java)
    }

    override fun extractState(): Any = value
}
