/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.test.guide

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
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
    fun modelCheckingTest() = ModelCheckingOptions()
        .actorsBefore(2) // number of operations before the parallel part
        .threads(2) // number of threads in the parallel part
        .actorsPerThread(2) // number of operations in each thread of the parallel part
        .actorsAfter(1) // number of operations after the parallel part
        .iterations(100) // generate 100 random concurrent scenarios
        .invocationsPerIteration(1000) // run each generated scenario 1000 times
        .check(this::class)
}