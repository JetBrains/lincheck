import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import kotlin.test.*

/*
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
 */

class NonParallelOpGroupTest : VerifierState() {
    private var producerCounter = 0
    private var consumerCounter = 0

    fun produce(count: Int): Int {
        producerCounter += count
        return producerCounter
    }

    fun produce2(count: Int): Int {
        producerCounter -= count
        return producerCounter
    }

    fun consume(): Int {
        consumerCounter++
        return consumerCounter
    }

    fun consume2(): Int {
        consumerCounter--
        return consumerCounter
    }

    override fun extractState(): Any {
        return Pair(producerCounter, consumerCounter)
    }

    @Test
    fun test_failing() {
        val f = LincheckStressConfiguration<NonParallelOpGroupTest>("NonParallelOpGroupTest").apply {
            iterations(500)
            invocationsPerIteration(100)
            actorsBefore(2)
            threads(2)
            actorsPerThread(5)
            actorsAfter(2)
            minimizeFailedScenario(false)

            initialState { NonParallelOpGroupTest() }
            stateRepresentation { this.toString() }

            operation(IntGen(""), NonParallelOpGroupTest::produce, "produce")
            operation(IntGen(""), NonParallelOpGroupTest::produce, "produce2")
            operation(NonParallelOpGroupTest::consume, "consume")
            operation(NonParallelOpGroupTest::consume, "consume2")
        }.checkImpl()
        assert(f != null && f is IncorrectResultsFailure) {
            "This test should fail with a incorrect results error"
        }
    }

    @Test
    fun test_working() {
        LincheckStressConfiguration<NonParallelOpGroupTest>("NonParallelOpGroupTest").apply {
            iterations(10)
            invocationsPerIteration(500)
            actorsBefore(2)
            threads(15000) // will be shrinked to 2
            actorsPerThread(5)
            actorsAfter(2)
            minimizeFailedScenario(false)

            initialState { NonParallelOpGroupTest() }
            stateRepresentation { this.toString() }

            operation(IntGen(""), NonParallelOpGroupTest::produce, "produce", nonParallelGroupName = "produce")
            operation(IntGen(""), NonParallelOpGroupTest::produce2, "produce2", nonParallelGroupName = "produce")
            operation(NonParallelOpGroupTest::consume, "consume", nonParallelGroupName = "consume")
            operation(NonParallelOpGroupTest::consume2, "consume2", nonParallelGroupName = "consume")
        }.runTest()
    }
}