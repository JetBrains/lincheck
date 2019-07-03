/*
 * #%L
 * Lincheck
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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.LogLevel
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.CostWithNextCostCounter
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.PathCostFunction.MAX
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.QuantitativeRelaxationVerifierConf
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.QuantitativeRelaxed
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.QuantitativelyRelaxedLinearizabilityVerifier
import org.junit.Test
import java.lang.AssertionError

private const val K = 2

@StressCTest(verifier = QuantitativelyRelaxedLinearizabilityVerifier::class)
@QuantitativeRelaxationVerifierConf(factor = K, pathCostFunc = MAX, costCounter = KStackRelaxedPushTest.CostCounter::class)
class KStackRelaxedPushTest {
    private val s = KStackSimulation<Int>(k = K)

    @QuantitativeRelaxed
    @Operation
    fun put(@Param(gen = IntGen::class) value: Int) = s.pushRelaxed(value)

    @Operation
    fun popOrNull() = s.popOrNull()

    @Test
    fun test() = LinChecker.check(KStackRelaxedPushTest::class.java)

    // Should have '(k: Int)' constructor
    data class CostCounter @JvmOverloads constructor(private val k: Int, private val s: List<Int> = emptyList()) {
        fun put(value: Int, result: Result): List<CostWithNextCostCounter<CostCounter>> {
            return (0..(k - 1).coerceAtMost(s.size)).map { i ->
                val sNew = ArrayList(s)
                sNew.add(i, value)
                CostWithNextCostCounter(CostCounter(k, sNew), i, i != 0)
            }
        }

        fun popOrNull(result: Result): CostCounter? {
            result as ValueResult
            if (s.isEmpty()) {
                return if (result.value == null) CostCounter(k) else null
            }
            if (s[0] != result.value) return null
            val sNew = ArrayList(s)
            sNew.removeAt(0)
            return CostCounter(k, sNew)
        }
    }
}

@StressCTest(verifier = QuantitativelyRelaxedLinearizabilityVerifier::class, threads = 2, actorsPerThread = 6, invocationsPerIteration = 1000, iterations = 100)
@LogLevel(LoggingLevel.DEBUG)
@QuantitativeRelaxationVerifierConf(factor = K, pathCostFunc = MAX, costCounter = KStackRelaxedPopIncorrectTest.CostCounter::class)
class KStackRelaxedPopIncorrectTest {
    private val s = KStackSimulation<Int>(k = K + 3)

    @Operation
    fun put(@Param(gen = IntGen::class) value: Int) = s.push(value)

    @QuantitativeRelaxed
    @Operation
    fun popOrNull() = s.popOrNullRelaxed()

    @Test(expected = AssertionError::class)
    fun test() = LinChecker.check(KStackRelaxedPopIncorrectTest::class.java)

    // Should have '(k: Int)' constructor
    data class CostCounter @JvmOverloads constructor(private val k: Int, private val s: List<Int> = emptyList()) {
        fun put(value: Int, result: Result): CostCounter {
            check(result is VoidResult)
            val sNew = ArrayList(s)
            sNew.add(0, value)
            return CostCounter(k, sNew)
        }

        fun popOrNull(result: Result): List<CostWithNextCostCounter<CostCounter>> {
            result as ValueResult
            if (result.value == null) {
                return if (s.isEmpty())
                    listOf(CostWithNextCostCounter(this, 0, false))
                else emptyList()
            }
            return (0..(k - 1).coerceAtMost(s.size - 1)).filter { i -> s[i] == result.value }.map { i ->
                val sNew = ArrayList(s)
                sNew.removeAt(i)
                CostWithNextCostCounter(CostCounter(k, sNew), i)
            }
        }
    }
}

@StressCTest(verifier = QuantitativelyRelaxedLinearizabilityVerifier::class)
@QuantitativeRelaxationVerifierConf(factor = K, pathCostFunc = MAX, costCounter = KStackRelaxedPushAndPopTest.CostCounter::class)
class KStackRelaxedPushAndPopTest {
    private val s = KStackSimulation<Int>(k = K)

    @QuantitativeRelaxed
    @Operation
    fun put(@Param(gen = IntGen::class) value: Int) = s.pushRelaxed(value)

    @QuantitativeRelaxed
    @Operation
    fun popOrNull() = s.popOrNullRelaxed()

    @Test
    fun test() = LinChecker.check(KStackRelaxedPushAndPopTest::class.java)

    // Should have '(k: Int)' constructor
    data class CostCounter @JvmOverloads constructor(private val k: Int, private val s: List<Int> = emptyList()) {
        fun put(value: Int, result: Result): List<CostWithNextCostCounter<CostCounter>> {
            return (0..(k - 1).coerceAtMost(s.size)).map { i ->
                val sNew = ArrayList(s)
                sNew.add(i, value)
                CostWithNextCostCounter(CostCounter(k, sNew), i, i != 0)
            }
        }

        fun popOrNull(result: Result): List<CostWithNextCostCounter<CostCounter>> {
            result as ValueResult
            if (result.value == null) {
                return if (s.isEmpty())
                    listOf(CostWithNextCostCounter(this, 0, false))
                else emptyList()
            }
            return (0..(k - 1).coerceAtMost(s.size - 1)).filter { i -> s[i] == result.value }.map { i ->
                val sNew = ArrayList(s)
                sNew.removeAt(i)
                CostWithNextCostCounter(CostCounter(k, sNew), i)
            }
        }
    }
}
