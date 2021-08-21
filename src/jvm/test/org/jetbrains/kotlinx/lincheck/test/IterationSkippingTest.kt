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

package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

class IterationSkippingTest : VerifierState() {
    var counter = 0

    @Operation
    fun incrementAndGet(): Int {
        ++counter
        return ++counter
    }

    @Test
    fun testIterationNumberPresent() {
        val options = ModelCheckingOptions().minimizeFailedScenario(false).iterations(10)
        val failure = options.checkImpl(this::class.java)
        check(failure != null) { "the test should fail" }
        val log = failure.toString()
        check("= Iteration 1 / 10 =" in log) { "The number of failing iteration should be present in the log" }
    }

    @Test
    fun testSkipIterations() {
        val options = ModelCheckingOptions()
            .minimizeFailedScenario(false)
            .iterations(10)
            .skipIterations(9)
        val failure = options.checkImpl(this::class.java)
        check(failure != null) { "the test should fail" }
        val log = failure.toString()
        check("= Iteration 10 / 10 =" in log) { "The number of failing iteration should be present in the log" }
        check("= Iteration 1 / 10 =" !in log)
    }

    @Test
    fun testSkipAllIterations() {
        val options = ModelCheckingOptions()
            .minimizeFailedScenario(false)
            .iterations(10)
            .skipIterations(10)
        val failure = options.checkImpl(this::class.java)
        check(failure == null) { "All iterations are skipped => should be no failure found" }
    }

    override fun extractState(): Any = counter
}