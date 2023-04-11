/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.test.representation

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.appendFailure
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.test.*
import org.jetbrains.kotlinx.lincheck.test.util.runModelCheckingTestAndCheckOutput
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.lang.IllegalStateException
import java.lang.StringBuilder
import java.util.concurrent.atomic.*

/**
 * This test checks that there are states in reported interleavings for model checking strategy.
 */
open class ModelCheckingStateReportingTest {
    @Volatile
    private var counter = AtomicInteger(0)

    @Operation
    fun operation(): Int {
        counter.incrementAndGet()
        return counter.getAndIncrement()
    }

    @StateRepresentation
    fun stateRepresentation() = counter.toString()

    @Test
    fun test() = runModelCheckingTestAndCheckOutput( "state_representation.txt") {
        actorsPerThread(1)
        actorsBefore(0)
        actorsAfter(0)
        requireStateEquivalenceImplCheck(false)
    }
}

/**
 * This test checks for incorrect scenarios states are reported.
 * States should be present after every part of the scenario (init, parallel, post).
 */
class StressStateReportingTest : VerifierState() {
    @Volatile
    private var counter = 0

    @Operation
    fun operation(): Int {
        ++counter
        return ++counter
    }

    override fun extractState(): Any = counter

    @StateRepresentation
    fun stateRepresentation() = counter.toString()

    @Test
    fun test() {
        val options = StressOptions()
            .actorsPerThread(1)
            .actorsBefore(0)
            .actorsAfter(0)
        val failure = options.checkImpl(this::class.java)
        check(failure != null) { "the test should fail" }
        val log = StringBuilder().appendFailure(failure).toString()
        check("STATE: 0" in log)
        check("STATE: 2" in log || "STATE: 3" in log || "STATE: 4" in log)
        checkTraceHasNoLincheckEvents(log)
    }
}

class StateRepresentationInParentClassTest : ModelCheckingStateReportingTest()

class TwoStateRepresentationFunctionsTest : VerifierState() {
    @Volatile
    private var counter = 0

    @Operation
    fun operation(): Int {
        ++counter
        return inc()
    }

    private fun inc(): Int = ++counter

    override fun extractState(): Any = counter

    @StateRepresentation
    fun stateRepresentation1() = counter.toString()

    @StateRepresentation
    fun stateRepresentation2() = counter.toString()

    @Test(expected = IllegalStateException::class)
    fun test() {
        ModelCheckingOptions()
            .actorsPerThread(1)
            .actorsBefore(0)
            .actorsAfter(0)
            .checkImpl(this::class.java)
    }
}

/**
 * Check LinCheck do not fail when state implementation is not deterministic.
 */
class NonDeterministicStateRepresentationTest() {
    @Volatile
    private var counter = AtomicInteger(0)

    @Operation
    fun operation(): Int {
        counter.incrementAndGet()
        return counter.getAndIncrement()
    }

    @StateRepresentation
    fun stateRepresentation() = "(${counter.get()}, ${Any()})"

    @Test
    fun test() {
        val options = ModelCheckingOptions()
            .actorsPerThread(1)
            .actorsBefore(0)
            .actorsAfter(0)
        val failure = options.checkImpl(this::class.java)
        check(failure != null) { "the test should fail" }
        check(failure is IncorrectResultsFailure) { "Incorrect results are expected, but ${failure::class.simpleName} failure found." }
    }
}
