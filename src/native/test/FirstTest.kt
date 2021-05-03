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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.verifier.*
import kotlin.native.concurrent.*
import kotlin.test.*

class ComplexMutableClass(val i: Int, val j: String) {

    override fun equals(other: Any?): Boolean {
        return if (other is ComplexMutableClass) {
            i == other.i && j == other.j;
        } else {
            false;
        }
    }

    override fun toString(): String {
        return "ComplexMutableClass $i $j"
    }
}

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

    fun complexOperation(): ComplexMutableClass {
        return ComplexMutableClass(239, "Hello, world!")
    }

    fun atomicIncrement() = atomicState.addAndGet(1)

    fun atomicDecrement() = atomicState.addAndGet(-1)
}

class FirstTest {
    @Test
    fun test_failing() {
        val f = LincheckStressConfiguration<TestClass>("FirstTest_1").apply {
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
        LincheckStressConfiguration<TestClass>("FirstTest_2").apply {
            iterations(10)
            invocationsPerIteration(500)
            actorsBefore(2)
            threads(3)
            actorsPerThread(5)
            actorsAfter(2)
            minimizeFailedScenario(false)

            initialState { TestClass() }
            stateRepresentation { this.toString() }

            operation(TestClass::atomicIncrement, "atomicIncrement")
            operation(TestClass::atomicDecrement, "atomicDecrement")
        }.runTest()
    }

    @Test
    fun test_many_threads() {
        LincheckStressConfiguration<TestClass>("FirstTest_3").apply {
            iterations(100)
            invocationsPerIteration(20)
            threads(7)
            actorsPerThread(2)
            minimizeFailedScenario(false)

            initialState { TestClass() }

            operation(TestClass::atomicIncrement, "atomicIncrement")
            operation(TestClass::atomicDecrement, "atomicDecrement")
            operation(TestClass::complexOperation, "complexOperation")
        }.runTest()
    }

    @Test
    fun test_complex() {
        LincheckStressConfiguration<TestClass>("FirstTest_3").apply {
            iterations(10)
            invocationsPerIteration(500)
            threads(4)
            minimizeFailedScenario(false)

            initialState { TestClass() }

            operation(TestClass::complexOperation, "complexOperation")
        }.runTest()
    }
}