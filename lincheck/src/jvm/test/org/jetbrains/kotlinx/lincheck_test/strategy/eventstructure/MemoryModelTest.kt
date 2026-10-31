/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure

import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.EventStructureStrategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.kotlinx.lincheck.util.ValueResult
import org.jetbrains.lincheck.datastructures.scenario
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Completeness and uniqueness of memory-access exploration by [EventStructureStrategy]. */
@RunWith(Parameterized::class)
class MemoryModelTest(private val memoryModel: MemoryModel) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun memoryModels() = listOf(MemoryModel.SequentialConsistency, MemoryModel.RelaxedJava)
    }

    @Test
    fun testRRWW() {
        val outcomes: Set<Pair<Int, Int>> = setOf(
            (0 to 0),
            (0 to 1),
            (1 to 0),
            (1 to 1)
        )
        litmusTest(assertSame(outcomes), memoryModel) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0;
            var r2 = 0;
            val t1 = thread {
                r1 = x.get();
                r2 = y.get()
            }
            val t2 = thread {
                y.set(1)
            }
            val t3 = thread {
                x.set(1)
            }
            t1.join()
            t2.join()
            t3.join()
            (r1 to r2)
        }
    }

    /* ======== Store Buffering ======== */


    @Test
    fun testSB() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(
//            (0 to 0),
            (0 to 1),
            (1 to 0),
            (1 to 1),
        )
        litmusTest(assertSame(expectedOutcomes), memoryModel) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0;
            var r2 = 0;
            val t1 = thread {
                x.set(1)
                r1 = y.get()
            }
            val t2 = thread {
                y.set(1)
                r2 = x.get()
            }
            t1.join()
            t2.join()
            (r1 to r2)
        }
    }

    // TODO: Not sure what naming convention we want here
    @Test
    fun testR_WR_W() {
        // These are all the sequentially consistent outcomes
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(
            (0 to 1),
            (0 to 2),
            (1 to 1),
            (1 to 2),
            (2 to 1),
            (2 to 2), // This outcome is not observed without the completeness fix
        )
        litmusTest(assertSame(expectedOutcomes), memoryModel) {
            val x = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            val t1 = thread {
                r1 = x.get()
            }
            val t2 = thread {
                x.set(1)
                r2 = x.get()
            }
            val t3 = thread {
                x.set(2)
            }
            t1.join()
            t2.join()
            t3.join()
            (r1 to r2)
        }
    }

    // TODO: Not sure what naming convention we want here
    @Test
    fun testR_RW_W() {
        // These are all the sequentially consistent outcomes
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(
            (0 to 0),
            (0 to 2),
            (1 to 0),
            (1 to 2),
            (2 to 0),
            (2 to 2), // This outcome is also missing without the completeness fix
        )
        litmusTest(assertSame(expectedOutcomes), memoryModel) {
            val x = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            val t1 = thread {
                r1 = x.get()
            }
            val t2 = thread {
                r2 = x.get()
                x.set(1)
            }
            val t3 = thread {
                x.set(2)
            }
            t1.join()
            t2.join()
            t3.join()
            (r1 to r2)
        }
    }

    // TODO: Not sure what naming convention we want here
    @Test
    fun testW_R_W() {
        val expectedOutcomes: Set<Int> = setOf(0, 1, 2)
        litmusTest(assertSame(expectedOutcomes), memoryModel) {
            val x = AtomicInteger(0)
            var r1 = 0
            val t1 = thread {
                x.set(1)
            }
            val t2 = thread {
                r1 = x.get()
            }
            val t3 = thread {
                x.set(2)
            }
            t1.join()
            t2.join()
            t3.join()
            r1
        }
    }

    @Test
    fun testReadBeforeOrderedWrites() {
        litmusTest(assertSame(setOf(0, 1, 2)), memoryModel, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var result = 0
            val reader = thread { result = x.get() }
            val writer = thread {
                x.set(1)
                x.set(2)
            }
            reader.join()
            writer.join()
            result
        }
    }

    @Test
    fun testOrderedAndIndependentReadsRevisitedToTheSameWrite() {
        val outcomes = setOf(
            Triple(0, 0, 0), Triple(0, 0, 1), Triple(0, 1, 0),
            Triple(0, 1, 1), Triple(1, 1, 0), Triple(1, 1, 1),
        )
        litmusTest(assertSame(outcomes), memoryModel, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = 0
            var second = 0
            var third = 0
            val orderedReader = thread {
                first = x.get()
                second = x.get()
            }
            val independentReader = thread { third = x.get() }
            val writer = thread { x.set(1) }
            orderedReader.join()
            independentReader.join()
            writer.join()
            Triple(first, second, third)
        }
    }

    @Test
    fun testRepeatedBackwardRevisit() {
        val outcomes = setOf(0 to 1, 1 to 0, 1 to 1, 2 to 0, 2 to 1)
        litmusTest(assertSame(outcomes), memoryModel, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var first = 0
            var second = 0
            val firstThread = thread {
                x.set(1)
                first = y.get()
            }
            val secondThread = thread {
                y.set(1)
                second = x.get()
                y.set(2)
            }
            firstThread.join()
            secondThread.join()
            first to second
        }
    }

    @Test
    fun testStableWriteOrderAcrossRevisits() {
        val outcomes = buildSet {
            for (first in 0..3) {
                for (second in listOf(0, 1, 3)) {
                    for (third in 0..1) add(Triple(first, second, third))
                }
            }
        }
        litmusTest(assertSame(outcomes), memoryModel, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var first = 0
            var second = 0
            var third = 0
            val reader = thread { first = x.get() }
            val writer = thread { x.set(1) }
            val readerWriter = thread {
                second = x.get()
                third = y.get()
                x.set(2)
            }
            val lastWriter = thread {
                x.set(3)
                y.set(1)
            }
            reader.join()
            writer.join()
            readerWriter.join()
            lastWriter.join()
            Triple(first, second, third)
        }
    }

    @Test
    fun testEqualValuedWritesHaveDistinctReadsFromGraphs() {
        // The read has three possible sources, even though both writes store the same value.
        litmusTest(assertSame(setOf(0, 1), executionCount = 3), memoryModel, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var result = -1
            val reader = thread { result = x.get() }
            val firstWriter = thread { x.set(1) }
            val secondWriter = thread { x.set(1) }
            reader.join()
            firstWriter.join()
            secondWriter.join()
            result
        }
    }

    @Test
    fun testRevisitAcrossActorBoundaries() {
        val program = scenario {
            parallel {
                thread {
                    actor(ActorState::getY)
                    actor(ActorState::getZ)
                }
                thread {
                    actor(ActorState::setZ, 12)
                    actor(ActorState::getY)
                    actor(ActorState::getY)
                }
                thread {
                    actor(ActorState::setY, 13)
                    actor(ActorState::setZ, 7)
                }
            }
        }
        val outcomes = setOf(
            listOf(0, 0, 0, 0), listOf(0, 0, 0, 13), listOf(0, 0, 13, 13),
            listOf(0, 7, 0, 0), listOf(0, 7, 0, 13), listOf(0, 7, 13, 13),
            listOf(0, 12, 0, 0), listOf(0, 12, 0, 13), listOf(0, 12, 13, 13),
            listOf(13, 0, 13, 13), listOf(13, 7, 0, 0), listOf(13, 7, 0, 13),
            listOf(13, 7, 13, 13), listOf(13, 12, 0, 0), listOf(13, 12, 0, 13), listOf(13, 12, 13, 13),
        )
        litmusTest(ActorState::class.java, program, assertSame(outcomes), memoryModel) { results ->
            results.parallelResults.flatMap { thread ->
                thread.mapNotNull { (it as? ValueResult)?.value as? Int }
            }
        }
    }

    class ActorState {
        private val y = AtomicInteger(0)
        private val z = AtomicInteger(0)

        fun getY(): Int = y.get()
        fun getZ(): Int = z.get()
        fun setY(value: Int) = y.set(value)
        fun setZ(value: Int) = z.set(value)
    }
}
