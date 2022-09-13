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
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.util.concurrent.atomic.*

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.experimental.runners.Enclosed


// TODO: looks like junit5 has better support for nested test classes
@RunWith(Enclosed::class)
class EventStructureStrategyTest {

    class RegisterTest {

        class Register {

            private var register : Int = 0

            fun write(value: Int) {
                register = value
            }

            fun read(): Int {
                return register
            }

        }

        private val read = Register::read
        private val write = Register::write

        @Test
        fun testWRW() {
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

            val outcomes: Set<Int> = setOf(0, 1, 2)

            litmusTest(Register::class.java, testScenario, outcomes) { results ->
                getReadResult(results.parallelResults[1][0])
            }
        }

    }

    class AtomicRegisterTest {

        class AtomicRegister {

            private val register = AtomicInteger()

            fun write(value: Int) {
                register.set(value)
            }

            fun read(): Int {
                return register.get()
            }

            fun compareAndSet(expected: Int, desired: Int): Boolean {
                return register.compareAndSet(expected, desired)
            }

            fun addAndGet(delta: Int): Int {
                return register.addAndGet(delta)
            }

            fun getAndAdd(delta: Int): Int {
                return register.getAndAdd(delta)
            }

        }

        private val read = AtomicRegister::read
        private val write = AtomicRegister::write
        private val compareAndSet = AtomicRegister::compareAndSet
        private val addAndGet = AtomicRegister::addAndGet
        private val getAndAdd = AtomicRegister::getAndAdd

        @Test
        fun testWRW() {
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
            val outcomes: Set<Int> = setOf(0, 1, 2)

            litmusTest(AtomicRegister::class.java, testScenario, outcomes) { results ->
                getReadResult(results.parallelResults[1][0])
            }
        }

        @Test
        fun testCAS() {
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

            val outcomes: Set<Triple<Boolean, Boolean, Int>> = setOf(
                Triple(true, false, 1),
                Triple(false, true, 1)
            )

            litmusTest(AtomicRegister::class.java, testScenario, outcomes) { results ->
                val r1 = getCASResult(results.parallelResults[0][0])
                val r2 = getCASResult(results.parallelResults[1][0])
                val r3 = getReadResult(results.postResults[0])
                Triple(r1, r2, r3)
            }
        }

        @Test
        fun testFAI() {
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

            val outcomes: Set<Triple<Int, Int, Int>> = setOf(
                Triple(0, 1, 2),
                Triple(1, 0, 2)
            )

            litmusTest(AtomicRegister::class.java, testScenario, outcomes) { results ->
                val r1 = getFAIResult(results.parallelResults[0][0])
                val r2 = getFAIResult(results.parallelResults[1][0])
                val r3 = getReadResult(results.postResults[0])
                Triple(r1, r2, r3)
            }
        }

        @Test
        fun testIAF() {
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

            val outcomes: Set<Triple<Int, Int, Int>> = setOf(
                Triple(1, 2, 2),
                Triple(2, 1, 2)
            )

            litmusTest(AtomicRegister::class.java, testScenario, outcomes) { results ->
                val r1 = getFAIResult(results.parallelResults[0][0])
                val r2 = getFAIResult(results.parallelResults[1][0])
                val r3 = getReadResult(results.postResults[0])
                Triple(r1, r2, r3)
            }
        }

    }

    class SharedMemoryTest {

        class SharedMemory {

            private val memory: Array<AtomicInteger> = Array(3) { AtomicInteger() }

            fun write(location: Int, value: Int) {
                memory[location].set(value)
            }

            fun read(location: Int): Int {
                return memory[location].get()
            }

            fun compareAndSet(location: Int, expected: Int, desired: Int): Boolean {
                return memory[location].compareAndSet(expected, desired)
            }

            fun addAndGet(location: Int, delta: Int): Int {
                return memory[location].addAndGet(delta)
            }

            fun getAndAdd(location: Int, delta: Int): Int {
                return memory[location].getAndAdd(delta)
            }

        }

        companion object {
            const val x = 0
            const val y = 1
            const val z = 2
        }

        private val read = SharedMemory::read
        private val write = SharedMemory::write
        private val compareAndSet = SharedMemory::compareAndSet
        private val addAndGet = SharedMemory::addAndGet
        private val getAndAdd = SharedMemory::getAndAdd

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
                val r1 = getReadResult(results.parallelResults[0][0])
                val r2 = getReadResult(results.parallelResults[0][1])
                (r1 to r2)
            }
        }

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
                val r1 = getReadResult(results.parallelResults[0][1])
                val r2 = getReadResult(results.parallelResults[1][1])
                (r1 to r2)
            }
        }

    }



}

private fun<OutcomeType> litmusTest(
    testClass: Class<*>,
    testScenario: ExecutionScenario,
    expectedOutcomes: Set<OutcomeType>,
    uniqueOutcomes: Boolean = true,
    outcome: (ExecutionResult) -> OutcomeType,
) {
    val outcomes: MutableSet<OutcomeType> = mutableSetOf()
    val verifier = createVerifier(testScenario) { results ->
        outcomes.add(outcome(results))
        true
    }
    val strategy = createStrategy(testClass, testScenario, verifier)
    val failure = strategy.run()
    assert(failure == null) { failure.toString() }
    assertEquals(expectedOutcomes, outcomes)
//    if (uniqueOutcomes) {
//        assertEquals(expectedOutcomes.size, strategy.consistentExecutions)
//    }
}

private fun createConfiguration(testClass: Class<*>) =
    EventStructureOptions()
//        .invocationTimeout(60 * 60 * 1000)
//        .invocationTimeout(10 * 1000)
        .createTestConfigurations(testClass)

private fun createStrategy(testClass: Class<*>, scenario: ExecutionScenario, verifier: Verifier): EventStructureStrategy {
    return createConfiguration(testClass)
        .createStrategy(
            testClass = testClass,
            scenario = scenario,
            verifier = verifier,
            validationFunctions = listOf(),
            stateRepresentationMethod = null,
        )
}

private fun createVerifier(testScenario: ExecutionScenario?, verify: (ExecutionResult) -> Boolean): Verifier =
    object : Verifier {

        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            require(testScenario == scenario)
            require(results != null)
            return verify(results)
        }

        override fun checkStateEquivalenceImplementation(): Boolean {
            return true
        }

    }

private fun getReadResult(result: Result): Int =
    (result as ValueResult).value as Int

private fun getCASResult(result: Result): Boolean =
    (result as ValueResult).value as Boolean

private fun getFAIResult(result: Result): Int =
    (result as ValueResult).value as Int