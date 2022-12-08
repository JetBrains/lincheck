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

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.io.Serializable

/**
 * This test checks that managed strategies do not switch
 * the execution thread at final field reads since they are
 * not interesting code locations for concurrent execution.
 * Otherwise, this test fails by timeout since
 * the number of invocations is set to [Int.MAX_VALUE].
 */
@ModelCheckingCTest(actorsBefore = 0, actorsAfter = 0, actorsPerThread = 50, invocationsPerIteration = Int.MAX_VALUE, iterations = 50, minimizeFailedScenario = false)
class FinalFieldReadingEliminationTest : VerifierState() {
    val primitiveValue: Int = 32
    val nonPrimitiveValue = ValueHolder(2)

    @Operation
    fun readPrimitive() = primitiveValue

    @Operation
    fun readNonPrimitive() = nonPrimitiveValue

    @Test(timeout = 100_000)
    fun test() {
        LinChecker.check(this::class.java)
    }

    override fun extractState(): Any = 0 // constant state

    data class ValueHolder(val value: Int) : Serializable
}
