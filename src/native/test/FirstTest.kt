/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.verifier.*
import kotlin.native.concurrent.*
import kotlin.test.*

class TestClass : VerifierState() {
    val atomicState: AtomicInt = AtomicInt(0)
    var regularState: Int = 0

    override fun extractState(): Any {
        return Pair(atomicState.value, regularState)
    }

    override fun toString(): String {
        return "$atomicState $regularState"
    }

    fun increment(): Int {
        regularState++
        return regularState
    }

    fun decrement(): Int {
        regularState--
        return regularState
    }

    fun atomicIncrement() = atomicState.addAndGet(1)

    fun atomicDecrement() = atomicState.addAndGet(-1)
}

class FirstTest {
    @Test
    fun test_failing() {
        val f = LincheckStressConfiguration<TestClass>().apply {
            iterations(300)
            invocationsPerIteration(50)
            actorsBefore(2)
            threads(3)
            actorsPerThread(2)
            actorsAfter(2)
            minimizeFailedScenario(false)

            initialState { TestClass() }
            stateRepresentation { this.toString() }

            operation(TestClass::increment, "add")
            operation({ this.decrement() }, "decrement")
            operation(IntGen(""), BooleanGen(""), { _, _ ->
                //println("Operation with arguments $i and $b has called")
            }, "do_nothing")
        }.checkImpl()

        assert(f != null && f is IncorrectResultsFailure) {
            "This test should fail with a incorrect results error"
        }
    }

    @Test
    fun test_working() {
        LincheckStressConfiguration<TestClass>().apply {
            iterations(50)
            invocationsPerIteration(5000)
            actorsBefore(2)
            threads(3)
            actorsPerThread(2)
            actorsAfter(2)
            minimizeFailedScenario(false)

            initialState { TestClass() }
            stateRepresentation { this.toString() }

            operation(TestClass::atomicIncrement, "atomicIncrement")
            operation(TestClass::atomicDecrement, "atomicDecrement")
        }.runTest()
    }

/*
    @Test
    fun test() {
        val testClass = TestClass("FirstTest") { FirstTest() }

        val actorGenerator1 = ActorGenerator(
            function = { instance, arguments ->
                (instance as TestClass).state.a()
            },
            parameterGenerators = listOf()
        )
        val actorGenerator2 = ActorGenerator(
            function = { instance, arguments ->
                (instance as TestClass).state.b()
            },
            parameterGenerators = listOf()
        )
        val actorGenerators: List<ActorGenerator> = listOf(actorGenerator1, actorGenerator2)
        val operationGroups: List<OperationGroup> = listOf()
        val validationFunctions: List<ValidationFunction> = listOf()
        val stateRepresentation: StateRepresentationFunction? = null
        val testStructure = CTestStructure(
            actorGenerators = actorGenerators,
            operationGroups = operationGroups,
            validationFunctions = validationFunctions,
            stateRepresentation = stateRepresentation
        )

        val options = StressOptions().run {
            iterations(1)
            invocationsPerIteration(50000)
            actorsBefore(2)
            threads(3)
            actorsPerThread(2)
            actorsAfter(2)
            minimizeFailedScenario(false)
        }

        LinChecker.check(testClass = testClass, testStructure = testStructure, options = options)
    }
*/
}