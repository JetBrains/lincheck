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
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

/**
 * This test checks that managed strategies do not try to switch
 * thread context at reads and writes of local objects.
 * In case a strategy does not have this optimization,
 * this test fails by timeout since the number of
 * invocations is set to [Int.MAX_VALUE].
 */
@Suppress("DEPRECATION_ERROR")
@ModelCheckingCTest(actorsBefore = 0, actorsAfter = 0, actorsPerThread = 50, invocationsPerIteration = Int.MAX_VALUE, iterations = 50)
class LocalObjectEliminationTest : VerifierState() {
    @Operation
    fun operation(): Int {
        val a = A(0, this, IntArray(2))
        a.any = a
        repeat(20) {
            a.value = it
        }
        a.array[1] = 54
        val b = A(a.value, a.any, a.array)
        b.value = 65
        repeat(20) {
            b.array[0] = it
        }
        a.any = b
        return (a.any as A).array.sum()
    }

    @Test(timeout = 100_000)
    fun test() {
        LinChecker.check(this::class.java)
    }

    override fun extractState(): Any = 0 // constant state

    private data class A(var value: Int, var any: Any, val array: IntArray)
}
