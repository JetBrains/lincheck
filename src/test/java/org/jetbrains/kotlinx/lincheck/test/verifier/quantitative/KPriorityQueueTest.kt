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
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.QuantitativelyRelaxedLinearizabilityVerifier
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.CostWithNextCostCounter
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.PathCostFunction.*
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.QuantitativeRelaxationVerifierConf
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.QuantitativeRelaxed
import org.junit.Test

@StressCTest(sequentialSpecification = KPriorityQueueTest.CostCounter::class,
             verifier = QuantitativelyRelaxedLinearizabilityVerifier::class,
             threads = 3, actorsPerThread = 3, actorsAfter = 3, actorsBefore = 3)
@Param(name = "push-value", gen = IntGen::class, conf = "1:20")
@LogLevel(LoggingLevel.INFO)
class KPriorityQueueTest {
    private val pq = KPriorityQueueSimulation(2)

    @Operation(params = ["push-value"])
    fun push(x: Int) = pq.push(x)

    @QuantitativeRelaxed
    @Operation
    fun poll(): Int? = pq.poll()

    @Test
    fun test() = LinChecker.check(KPriorityQueueTest::class.java)

    @QuantitativeRelaxationVerifierConf(factor = 2, pathCostFunc = PHI_INTERVAL_RESTRICTED_MAX)
    data class CostCounter @JvmOverloads constructor(
        private val k: Int,
        private val pq: List<Int> = emptyList()
    ) {
        fun push(value: Int, result: Result): CostCounter {
            check(result is VoidResult)
            val pqNew = ArrayList(pq)
            pqNew.add(0, value)
            pqNew.sort()
            return CostCounter(k, pqNew)
        }

        fun poll(result: Result): List<CostWithNextCostCounter<CostCounter>> {
            result as ValueResult
            if (pq.isEmpty()) {
                return if (result.value == null) listOf(CostWithNextCostCounter(this, 0, false))
                else emptyList()
            } else {
                val edges: MutableList<CostWithNextCostCounter<CostCounter>> = mutableListOf()
                val predicate = result.value != pq[0]
                val it = pq.iterator()
                var cost = 0
                while (it.hasNext() && cost < k) {
                    val value = it.next()
                    if (value == result.value) {
                        val pqNew = ArrayList(pq)
                        pqNew.remove(value)
                        edges.add(CostWithNextCostCounter(CostCounter(k, pqNew), cost, predicate))
                    }
                    cost++
                }
                return edges
            }
        }
    }
}
