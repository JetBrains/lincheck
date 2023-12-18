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

package org.jetbrains.kotlinx.lincheck.test.guide

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*

class CounterTest {
    private val c = Counter()

    @Operation
    fun inc() = c.inc()

    @Operation
    fun get() = c.get()

    @StateRepresentation
    fun stateRepresentation() = c.get().toString()

    // @Test TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun stressTest() = StressOptions() // stress testing options
        .actorsBefore(2) // number of operations before the parallel part
        .threads(2) // number of threads in the parallel part
        .actorsPerThread(2) // number of operations in each thread of the parallel part
        .actorsAfter(1) // number of operations after the parallel part
        .iterations(100) // generate 100 random concurrent scenarios
        .invocationsPerIteration(1000) // run each generated scenario 1000 times
        .check(this::class) // run the test

    // @Test TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun modelCheckingTest() = EventStructureOptions() // ModelCheckingOptions()
        .actorsBefore(2) // number of operations before the parallel part
        .threads(2) // number of threads in the parallel part
        .actorsPerThread(2) // number of operations in each thread of the parallel part
        .actorsAfter(1) // number of operations after the parallel part
        .iterations(100) // generate 100 random concurrent scenarios
        .invocationsPerIteration(1000) // run each generated scenario 1000 times
        .check(this::class)
}