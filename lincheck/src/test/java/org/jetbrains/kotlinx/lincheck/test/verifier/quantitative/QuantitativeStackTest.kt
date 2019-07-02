/*-
 * #%L
 * lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
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
package org.jetbrains.kotlinx.lincheck.test.verifier.quantitative

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.Result
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.test.verifier.quasi.KRelaxedPopStack
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.*
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.*
import org.junit.Test

@StressCTest(threads = 2, actorsPerThread = 10, actorsBefore = 5, actorsAfter = 5,  invocationsPerIteration = 1000, iterations = 10, verifier = QuantitativeRelaxationVerifier::class)
@QuantitativeRelaxationVerifierConf(
        factor = 3,
        pathCostFunc = PathCostFunction.MAX,
        costCounter = QuantitativeStackTest.CostCounter::class
)
@Param(name = "push", gen = IntGen::class, conf = "1:20")
class QuantitativeStackTest {
    private val s = KRelaxedPopStack<Int>(2)

    @Operation(params = ["push"])
    fun push(x: Int) = s.push(x)

    @Operation(params = ["push"])
    fun push1(x: Int) = s.push1(x)

    @Operation(params = ["push"])
    fun push2(x: Int) = s.push2(x)

    @QuantitativeRelaxed
    @Operation
    fun pop(): Int? = s.pop()

    @Test
    fun test() = LinChecker.check(QuantitativeStackTest::class.java)

    // Should have '(k: Int)' constructor
    data class CostCounter @JvmOverloads constructor(
            private val k: Int,
            private val s: List<Int> = emptyList()
    ) {
        fun push(value: Int, result: Result): CostCounter {
            check(result.type == Result.Type.VOID)
            val sNew = ArrayList(s)
            sNew.add(0, value)
            return CostCounter(k, sNew)
        }

        fun push1(value: Int, result: Result): CostCounter {
            check(result.type == Result.Type.VOID)
            val sNew = ArrayList(s)
            sNew.add(0, value)
            return CostCounter(k, sNew)
        }

        fun push2(value: Int, result: Result): CostCounter {
            check(result.type == Result.Type.VOID)
            val sNew = ArrayList(s)
            sNew.add(0, value)
            return CostCounter(k, sNew)
        }

        fun pop(result: Result): List<CostWithNextCostCounter<CostCounter>> {
            if (result.value == null) {
                return if (s.isEmpty())
                    listOf(CostWithNextCostCounter(this, 0))
                else emptyList()
            }
            return (0..(k - 1).coerceAtMost(s.size - 1))
                    .filter { i -> s[i] == result.value }
                    .map { i ->
                        val sNew = ArrayList(s)
                        sNew.removeAt(i)
                        CostWithNextCostCounter(CostCounter(k, sNew), i)
                    }
        }
    }
}
