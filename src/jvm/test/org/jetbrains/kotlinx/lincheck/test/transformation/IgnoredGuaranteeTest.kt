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
package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

/**
 * This test checks that managed strategies do not try to switch
 * thread context inside methods that are marked as ignored.
 * The ignored method should not be considered as a one that
 * performs reads or writes, so that there is no need to switch
 * the execution after an ignored method invocation.
 *
 * If the ignored method is not processed properly, this test fails
 * by timeout since the number of invocations is set to Int.MAX_VALUE.
 */
@Suppress("DEPRECATION_ERROR")
@ModelCheckingCTest(actorsBefore = 0, actorsAfter = 0, actorsPerThread = 100, invocationsPerIteration = Int.MAX_VALUE, iterations = 50)
class IgnoredGuaranteeTest : VerifierState() {
    var value: Int = 0

    @Operation
    fun operation() = inc()

    private fun inc(): Int {
        return value++
    }

    @Test(timeout = 100_000)
    fun test() {
        val options = ModelCheckingOptions()
                .actorsBefore(0)
                .actorsAfter(0)
                .actorsPerThread(100)
                .iterations(1)
                .invocationsPerIteration(Int.MAX_VALUE)
                .addGuarantee(forClasses(this::class.java.name).methods("inc").ignore())
        options.check(this::class.java)
    }

    override fun extractState(): Any = value
}
