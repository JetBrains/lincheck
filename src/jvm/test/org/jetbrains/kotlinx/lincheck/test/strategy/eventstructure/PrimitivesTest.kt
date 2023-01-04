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
import java.util.concurrent.locks.LockSupport.*

import org.junit.Test
import org.junit.Ignore

/**
 * These tests check that [EventStructureStrategy] correctly handles all basic concurrent primitives.
 * In particular, we check the partial order reduction optimality with respect to these primitives,
 * i.e. we check that the strategy does not explore redundant interleavings.
 */
class PrimitivesTest {

    class PlainVariable {
        private var variable: Int = 0

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
            getValue<Int>(results.parallelResults[1][0])
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
            getValue<Int>(results.parallelResults[1][0])
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
            val r1 = getValue<Boolean>(results.parallelResults[0][0])
            val r2 = getValue<Boolean>(results.parallelResults[1][0])
            val r3 = getValue<Int>(results.postResults[0])
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
            val r1 = getValue<Int>(results.parallelResults[0][0])
            val r2 = getValue<Int>(results.parallelResults[1][0])
            val r3 = getValue<Int>(results.postResults[0])
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
            val r1 = getValue<Int>(results.parallelResults[0][0])
            val r2 = getValue<Int>(results.parallelResults[1][0])
            val r3 = getValue<Int>(results.postResults[0])
            Triple(r1, r2, r3)
        }
    }

    class GlobalAtomicVariable {

        fun write(value: Int) {
            globalVariable.set(value)
        }

        fun read(): Int {
            return globalVariable.get()
        }

        fun compareAndSet(expected: Int, desired: Int): Boolean {
            return globalVariable.compareAndSet(expected, desired)
        }

        fun addAndGet(delta: Int): Int {
            return globalVariable.addAndGet(delta)
        }

        fun getAndAdd(delta: Int): Int {
            return globalVariable.getAndAdd(delta)
        }
    }

    @Test
    @Ignore
    fun testGlobalAtomicVariable() {
        val read = GlobalAtomicVariable::read
        val write = GlobalAtomicVariable::write
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
        litmusTest(GlobalAtomicVariable::class.java, testScenario, outcomes) { results ->
            getValue<Int>(results.parallelResults[1][0])
        }
    }

    // TODO: handle IntRef (var variables accessed from multiple threads)

    class SynchronizedVariable {

        private var variable: Int = 0

        @Synchronized
        fun write(value: Int) {
            variable = value
        }

        @Synchronized
        fun read(): Int {
            return variable
        }

        @Synchronized
        fun waitAndRead(): Int {
            // TODO: handle spurious wake-ups?
            (this as Object).wait()
            return variable
        }

        @Synchronized
        fun writeAndNotify(value: Int) {
            variable = value
            (this as Object).notify()
        }

        @Synchronized
        fun compareAndSet(expected: Int, desired: Int): Boolean {
            return if (variable == expected) {
                variable = desired
                true
            } else false
        }

        @Synchronized
        fun addAndGet(delta: Int): Int {
            variable += delta
            return variable
        }

        @Synchronized
        fun getAndAdd(delta: Int): Int {
            val value = variable
            variable += delta
            return value
        }

    }

    @Test
    fun testSynchronized() {
        val read = SynchronizedVariable::read
        val addAndGet = SynchronizedVariable::addAndGet
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
        // naive strategy may explore more interleavings (due to context-switches before/after locks)
        val outcomes: Set<Triple<Int, Int, Int>> = setOf(
            Triple(1, 2, 2),
            Triple(2, 1, 2)
        )
        litmusTest(SynchronizedVariable::class.java, testScenario, outcomes) { results ->
            val r1 = getValue<Int>(results.parallelResults[0][0])
            val r2 = getValue<Int>(results.parallelResults[1][0])
            val r3 = getValue<Int>(results.postResults[0])
            Triple(r1, r2, r3)
        }
    }

    @Test
    fun testWaitNotify() {
        val writeAndNotify = SynchronizedVariable::writeAndNotify
        val waitAndRead = SynchronizedVariable::waitAndRead
        val testScenario = scenario {
            parallel {
                thread {
                    actor(writeAndNotify, 1)
                }
                thread {
                    actor(waitAndRead)
                }
            }
        }
        // strategy should explore only 1 interleaving
        // naive strategy may explore more interleavings (due to context-switches before/after locks)
        val outcomes = setOf(1)
        // TODO: investigate why there are 2 executions instead of 1
        litmusTest(SynchronizedVariable::class.java, testScenario, outcomes, executionCount = 2) { results ->
            getValue<Int>(results.parallelResults[1][0])
        }
    }

    class ParkLatchedVariable {

        private var variable: Int = 0

        @Volatile
        private var parkedThread: Thread? = null

        @Volatile
        private var delivered: Boolean = false

        fun parkAndRead(): Int? {
            // TODO: handle spurious wake-ups?
            parkedThread = Thread.currentThread()
            return if (delivered) {
                park()
                variable
            } else null
        }

        fun writeAndUnpark(value: Int) {
            variable = value
            val thread = parkedThread
            if (thread != null)
                delivered = true
            unpark(thread)
        }

    }

    @Test
    fun testParking() {
        val writeAndUnpark = ParkLatchedVariable::writeAndUnpark
        val parkAndRead = ParkLatchedVariable::parkAndRead
        val testScenario = scenario {
            parallel {
                thread {
                    actor(writeAndUnpark, 1)
                }
                thread {
                    actor(parkAndRead)
                }
            }
        }
        // strategy should explore only 3 interleaving
        // naive strategy may explore more interleavings (due to context-switches before/after park)
        val outcomes = setOf(null, 1)
        litmusTest(ParkLatchedVariable::class.java, testScenario, outcomes, executionCount = 3) { results ->
            getValue<Int?>(results.parallelResults[1][0])
        }
    }

}

// TODO: In the future we would likely want to switch to atomicfu primitives.
//   However, atomicfu currently does not support various access modes that we intend to test here.
private val globalVariable = AtomicInteger(0)