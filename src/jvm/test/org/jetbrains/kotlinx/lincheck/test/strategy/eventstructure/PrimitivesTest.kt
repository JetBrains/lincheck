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

package org.jetbrains.kotlinx.lincheck.test.strategy.eventstructure

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*

import java.util.concurrent.atomic.*

import org.junit.Test

/**
 * These tests check that [EventStructureStrategy] correctly handles all basic concurrent primitives.
 * In particular, we check the partial order reduction optimality with respect to these primitives,
 * i.e. we check that the strategy does not explore redundant interleavings.
 */
class PrimitivesTest {

    class PlainVariable {
        private var variable : Int = 0

        fun write(value: Int) {
            variable = value
        }

        fun read(): Int {
            return variable
        }
    }

    @Test
    fun testPlainVariable() {
        val write = PlainVariable::write
        val read = PlainVariable::read
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, 1)
                }
                thread {
                    actor(read)
                }
                thread {
                    actor(write, 2)
                }
            }
        }
        // strategy should explore only 3 interleavings
        // (by the number of distinct possible read values)
        // naive strategy would explore 6 interleavings
        // TODO: when we will implement various access modes,
        //   we should probably report races on plain variables as errors (or warnings at least)
        val outcomes: Set<Int> = setOf(0, 1, 2)
        litmusTest(PlainVariable::class.java, testScenario, outcomes) { results ->
            getReadValue(results.parallelResults[1][0])
        }
    }

    class AtomicVariable {
        // TODO: In the future we would likely want to switch to atomicfu primitives.
        //   However, atomicfu currently does not support various access modes that we intend to test here.
        private val variable = AtomicInteger()

        fun write(value: Int) {
            variable.set(value)
        }

        fun read(): Int {
            return variable.get()
        }

        fun compareAndSet(expected: Int, desired: Int): Boolean {
            return variable.compareAndSet(expected, desired)
        }

        fun addAndGet(delta: Int): Int {
            return variable.addAndGet(delta)
        }

        fun getAndAdd(delta: Int): Int {
            return variable.getAndAdd(delta)
        }
    }

    @Test
    fun testAtomicVariable() {
        val read = AtomicVariable::read
        val write = AtomicVariable::write
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, 1)
                }
                thread {
                    actor(read)
                }
                thread {
                    actor(write, 2)
                }
            }
        }
        // strategy should explore only 3 interleavings
        // (by the number of distinct possible read values)
        // naive strategy would explore 6 interleavings
        val outcomes: Set<Int> = setOf(0, 1, 2)
        litmusTest(AtomicVariable::class.java, testScenario, outcomes) { results ->
            getReadValue(results.parallelResults[1][0])
        }
    }

    @Test
    fun testCompareAndSet() {
        val read = AtomicVariable::read
        val compareAndSet = AtomicVariable::compareAndSet
        val testScenario = scenario {
            parallel {
                thread {
                    actor(compareAndSet, 0, 1)
                }
                thread {
                    actor(compareAndSet, 0, 1)
                }
            }
            post {
                actor(read)
            }
        }
        // strategy should explore only 2 interleavings
        // naive strategy also explores 2 interleavings
        val outcomes: Set<Triple<Boolean, Boolean, Int>> = setOf(
            Triple(true, false, 1),
            Triple(false, true, 1)
        )
        litmusTest(AtomicVariable::class.java, testScenario, outcomes) { results ->
            val r1 = getCASValue(results.parallelResults[0][0])
            val r2 = getCASValue(results.parallelResults[1][0])
            val r3 = getReadValue(results.postResults[0])
            Triple(r1, r2, r3)
        }
    }

    @Test
    fun testGetAndAdd() {
        val read = AtomicVariable::read
        val getAndAdd = AtomicVariable::getAndAdd
        val testScenario = scenario {
            parallel {
                thread {
                    actor(getAndAdd, 1)
                }
                thread {
                    actor(getAndAdd, 1)
                }
            }
            post {
                actor(read)
            }
        }
        // strategy should explore only 2 interleavings
        // naive strategy also explores 2 interleavings
        val outcomes: Set<Triple<Int, Int, Int>> = setOf(
            Triple(0, 1, 2),
            Triple(1, 0, 2)
        )
        litmusTest(AtomicVariable::class.java, testScenario, outcomes) { results ->
            val r1 = getFAIValue(results.parallelResults[0][0])
            val r2 = getFAIValue(results.parallelResults[1][0])
            val r3 = getReadValue(results.postResults[0])
            Triple(r1, r2, r3)
        }
    }

    @Test
    fun testAddAndGet() {
        val read = AtomicVariable::read
        val addAndGet = AtomicVariable::addAndGet
        val testScenario = scenario {
            parallel {
                thread {
                    actor(addAndGet, 1)
                }
                thread {
                    actor(addAndGet, 1)
                }
            }
            post {
                actor(read)
            }
        }
        // strategy should explore only 2 interleavings
        // naive strategy also explores 2 interleavings
        val outcomes: Set<Triple<Int, Int, Int>> = setOf(
            Triple(1, 2, 2),
            Triple(2, 1, 2)
        )
        litmusTest(AtomicVariable::class.java, testScenario, outcomes) { results ->
            val r1 = getFAIValue(results.parallelResults[0][0])
            val r2 = getFAIValue(results.parallelResults[1][0])
            val r3 = getReadValue(results.postResults[0])
            Triple(r1, r2, r3)
        }
    }

    class IntrinsicLockTest {

        fun withLock(block: () -> Any): Any {
            return synchronized(this) {
                block()
            }
        }

        fun lockAndWait(afterWait: () -> Any): Any {
            return synchronized(this) {
                (this as java.lang.Object).wait()
                afterWait()
            }
        }

        fun lockAndNotify(notifyAll: Boolean, beforeNotify: () -> Any): Any {
            return synchronized(this) {
                beforeNotify().also {
                    (this as java.lang.Object)
                    if (notifyAll) notifyAll() else notify()
                }
            }
        }

    }

    @Test
    fun testSynchronized() {
        var x = 0
        var y = 0
        val withLock = IntrinsicLockTest::withLock
        val testScenario = scenario {
            parallel {
                thread {
                    actor(withLock, { x = 1; y })
                }
                thread {
                    actor(withLock, { y = 1; x })
                }
            }
        }
        // strategy should explore only 2 interleavings
        // naive strategy also explores 2 interleavings
        val outcomes: Set<Pair<Int, Int>> = setOf(
            (0 to 1),
            (1 to 0),
        )
        litmusTest(IntrinsicLockTest::class.java, testScenario, outcomes) { results ->
            val r1 = getValue<Int>(results.parallelResults[0][0])
            val r2 = getValue<Int>(results.parallelResults[1][0])
            Pair(r1, r2)
        }
    }

    @Test
    fun testWaitNotify() {
        var x = 0
        val lockAndWait = IntrinsicLockTest::lockAndWait
        val lockAndNotify = IntrinsicLockTest::lockAndNotify
        val testScenario = scenario {
            parallel {
                thread {
                    actor(lockAndWait, { x })
                }
                thread {
                    actor(lockAndNotify, false, { x = 1 })
                }
            }
        }
        // strategy should explore only 1 interleaving
        // naive strategy also explores 1 interleaving
        val outcomes = setOf(
            (1 to Unit),
        )
        litmusTest(IntrinsicLockTest::class.java, testScenario, outcomes) { results ->
            val r1 = getValue<Int>(results.parallelResults[0][0])
            val r2 = getValue<Unit>(results.parallelResults[1][0])
            Pair(r1, r2)
        }
    }

}