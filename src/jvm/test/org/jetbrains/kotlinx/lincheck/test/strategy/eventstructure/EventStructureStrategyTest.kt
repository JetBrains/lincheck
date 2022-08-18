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

class ReadWriteRegister {

    private var register : Int = 0

    fun write(value: Int) {
        register = value
    }

    fun read(): Int {
        return register
    }

}

class AtomicReadWriteRegister {

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

class EventStructureStrategyTest {

    private val read = ReadWriteRegister::read
    private val write = ReadWriteRegister::write

    private val atomicRead = AtomicReadWriteRegister::read
    private val atomicWrite = AtomicReadWriteRegister::write

    private val compareAndSet = AtomicReadWriteRegister::compareAndSet

    private val addAndGet = AtomicReadWriteRegister::addAndGet
    private val getAndAdd = AtomicReadWriteRegister::getAndAdd

    private fun getReadValue(result: Result): Int =
        (result as ValueResult).value as Int

    private fun getCASResult(result: Result): Boolean =
        (result as ValueResult).value as Boolean

    private fun getFAIResult(result: Result): Int =
        (result as ValueResult).value as Int

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

        val expectedReadResults = setOf(0, 1, 2)
        val readResults: MutableSet<Int> = mutableSetOf()
        val verifier = createVerifier(testScenario) { results ->
            val readResult = getReadValue(results.parallelResults[1][0])
            readResults.add(readResult)
            readResult in expectedReadResults
        }

        val strategy = createStrategy(ReadWriteRegister::class.java, testScenario, verifier)
        val failure = strategy.run()
        assert(failure == null) { failure.toString() }
        assert(readResults == expectedReadResults)
    }

    @Test
    fun testAtomicWRW() {
        val testScenario = scenario {
            parallel {
                thread {
                    actor(atomicWrite, 1)
                }
                thread {
                    actor(atomicRead)
                }
                thread {
                    actor(atomicWrite, 2)
                }
            }
        }

        val expectedReadResults = setOf(0, 1, 2)
        val readResults: MutableSet<Int> = mutableSetOf()
        val verifier = createVerifier(testScenario) { results ->
            val readResult = getReadValue(results.parallelResults[1][0])
            readResults.add(readResult)
            readResult in expectedReadResults
        }

        val strategy = createStrategy(ReadWriteRegister::class.java, testScenario, verifier)
        val failure = strategy.run()
        assert(failure == null) { failure.toString() }
        assert(readResults == expectedReadResults)
    }

    @Test
    fun testAtomicCAS() {
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
                actor(atomicRead)
            }
        }

        val verifier = createVerifier(testScenario) { results ->
            var succeededCAS = 0
            if (getCASResult(results.parallelResults[0][0])) succeededCAS++
            if (getCASResult(results.parallelResults[1][0])) succeededCAS++
             val readResult = getReadValue(results.postResults[0])
            (succeededCAS == 1) && (readResult == 1)
        }

        val strategy = createStrategy(AtomicReadWriteRegister::class.java, testScenario, verifier)
        val failure = strategy.run()
        assert(failure == null) { failure.toString() }
    }

    @Test
    fun testAtomicFAI() {
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
                actor(atomicRead)
            }
        }

        val verifier = createVerifier(testScenario) { results ->
            val r1 = getFAIResult(results.parallelResults[0][0])
            val r2 = getFAIResult(results.parallelResults[1][0])
             val r3 = getReadValue(results.postResults[0])
            ((r1 == 0 && r2 == 1) || (r1 == 1 && r2 == 0)) && (r3 == 2)
        }

        val strategy = createStrategy(AtomicReadWriteRegister::class.java, testScenario, verifier)
        val failure = strategy.run()
        assert(failure == null) { failure.toString() }
    }

    @Test
    fun testAtomicIAF() {
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
                actor(atomicRead)
            }
        }

        val verifier = createVerifier(testScenario) { results ->
            val r1 = getFAIResult(results.parallelResults[0][0])
            val r2 = getFAIResult(results.parallelResults[1][0])
            val r3 = getReadValue(results.postResults[0])
            ((r1 == 1 && r2 == 2) || (r1 == 2 && r2 == 1)) && (r3 == 2)
        }

        val strategy = createStrategy(AtomicReadWriteRegister::class.java, testScenario, verifier)
        val failure = strategy.run()
        assert(failure == null) { failure.toString() }
    }

    private fun createConfiguration(testClass: Class<*>) =
        EventStructureOptions().createTestConfigurations(testClass)

    private fun createStrategy(testClass: Class<*>, scenario: ExecutionScenario, verifier: Verifier): EventStructureStrategy {
        return createConfiguration(testClass).createStrategy(
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

}