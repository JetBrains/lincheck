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
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstruct.*
import org.jetbrains.kotlinx.lincheck.verifier.*
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

class EventStructureStrategyTest {

    private val read = ReadWriteRegister::read
    private val write = ReadWriteRegister::write

    private fun getReadValue(result: Result): Int =
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

        val expectedReadResults = setOf(1, 2, 3)
        val readResults: MutableSet<Int> = mutableSetOf()
        val verifier = object : Verifier {

            override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
                require(testScenario == scenario)
                require(results != null)
                val readResult = getReadValue(results.parallelResults[1][0])
                readResults.add(readResult)
                return readResult in expectedReadResults
            }

            override fun checkStateEquivalenceImplementation(): Boolean {
                return true
            }
        }

        val strategy = createStrategy(testScenario, verifier)
        val failure = strategy.run()
        assert(failure == null)
        assert(readResults == expectedReadResults)
    }

    private val configuration = EventStructureOptions().createTestConfigurations(ReadWriteRegister::class.java)

    private fun createStrategy(scenario: ExecutionScenario, verifier: Verifier): EventStructureStrategy {
        return configuration.createStrategy(
            testClass = ReadWriteRegister::class.java,
            scenario = scenario,
            verifier = verifier,
            validationFunctions = listOf(),
            stateRepresentationMethod = null
        )
    }

}