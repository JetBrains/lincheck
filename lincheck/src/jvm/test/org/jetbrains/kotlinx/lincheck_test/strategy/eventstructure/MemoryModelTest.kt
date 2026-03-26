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

import org.junit.Assert
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import java.util.concurrent.atomic.*

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.lincheck.datastructures.actor
import org.jetbrains.lincheck.datastructures.scenario

import org.junit.Test
import kotlin.concurrent.thread

/**
 * These tests check that [EventStructureStrategy] adheres to the weak memory model.
 * It contains various litmus tests to check for specific weak behaviors.
 */
class MemoryModelTest {

    private val read = SharedMemory::read
    private val write = SharedMemory::write
    private val compareAndSet = SharedMemory::compareAndSet
    private val fetchAndAdd = SharedMemory::fetchAndAdd

    companion object {
        const val x = 0
        const val y = 1
        const val z = 2
    }

    @Test
    fun testRRWW() {
        val testScenario = scenario {
            parallel {
                thread {
                    actor(read, x)
                    actor(read, y)
                }
                thread {
                    actor(write, y, 1)
                }
                thread {
                    actor(write, x, 1)
                }
            }
        }
        val outcomes: Set<Pair<Int, Int>> = setOf(
            (0 to 0),
            (0 to 1),
            (1 to 0),
            (1 to 1)
        )
        litmusTest(SharedMemory::class.java, testScenario, outcomes) { results ->
            val r1 = getValue<Int>(results.parallelResults[0][0]!!)
            val r2 = getValue<Int>(results.parallelResults[0][1]!!)
            (r1 to r2)
        }
    }

    /* ======== Store Buffering ======== */

    @Test
    fun testSB() {
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, x, 1)
                    actor(read, y)
                }
                thread {
                    actor(write, y, 1)
                    actor(read, x)
                }
            }
        }
        val outcomes: Set<Pair<Int, Int>> = setOf(
            (0 to 1),
            (1 to 0),
            (1 to 1)
        )
        litmusTest(SharedMemory::class.java, testScenario, outcomes) { results ->
            val r1 = getValue<Int>(results.parallelResults[0][1]!!)
            val r2 = getValue<Int>(results.parallelResults[1][1]!!)
            (r1 to r2)
        }
    }

    //OG version
    @Ignore
    @Test
    fun testRRWWOpaque2() {
        class TestRRRW {
            val x = AtomicInteger(0)
            fun  one(): Pair<Int,Int> {
                val r1 = x.getOpaque()
                val r2 = x.getOpaque()
                return r1 to r2
            }
            fun two() {
                x.setOpaque(1)
            }
        }
        val testScenario = scenario {
            thread {
                actor(TestRRRW::one)
            }
            thread {
                actor(TestRRRW::two)
            }
        }
        // Do not focus on the expected outcomes too much. You can leave them empty if confused
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(
            Pair(0, 0),
            Pair(0, 1),
            Pair(1, 0),
            Pair(1, 1),
        )
        litmusTest(SharedMemory::class.java, testScenario, expectedOutcomes) { results ->
            val r1 = getValue<Pair<Int, Int>>(results.parallelResults[0][0]!!).first
            val r2 = getValue<Pair<Int, Int>>(results.parallelResults[0][0]!!).first
            Pair(r1,r2)
        }
    }

    // New version
    @Test
    fun testRRWOpaque() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(
            (0 to 0),
            (0 to 1),
            (1 to 0),
//            (1 to 1), TODO: fix exploration strat to unlock this outcome
        )
        litmustTestv2(expectedOutcomes) {
            val x = AtomicInteger(0)
            var r1 = 0;
            var r2 = 0;
            val t1 = thread { r1 = x.getOpaque() }
            val t2 = thread { r2 = x.getOpaque() }
            val t3 = thread { x.setOpaque(1) }
            t1.join()
            t2.join()
            t3.join()
            (r1 to r2)
        }
    }
}

internal class SharedMemory(size: Int = 16) {
    // TODO: use AtomicIntegerArray once it is fixed
    // TODO: In the future we would likely want to switch to atomicfu primitives.
    //   However, atomicfu currently does not support various access modes that we intend to test here.
    private val memory = Array(size) { AtomicInteger() }

    val size: Int
        get() = memory.size

    fun write(location: Int, value: Int) {
        memory[location].set(value)
    }

    fun read(location: Int): Int {
        return memory[location].get()
    }

    // TODO: use `compareAndExchange` once Java 9 is available?
    fun compareAndSet(location: Int, expected: Int, desired: Int): Boolean {
        return memory[location].compareAndSet(expected, desired)
    }

    fun fetchAndAdd(location: Int, delta: Int): Int {
        return memory[location].getAndAdd(delta)
    }
}