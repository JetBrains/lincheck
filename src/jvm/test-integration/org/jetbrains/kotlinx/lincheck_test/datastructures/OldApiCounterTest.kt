/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

/*
 * Checks that the old data-structures testing API from `org.jetbrains.kotlinx.lincheck` is still working.
 */

// Supress deprecation of the old API.
@file:Suppress("DEPRECATION")

package org.jetbrains.kotlinx.lincheck_test.datastructures

import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test

class OldApiCounterTest {
    private val c = Counter() // initial state

    // operations on the Counter
    @Operation
    fun inc() = c.inc()

    @Operation
    fun get() = c.get()

    //@Test // TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun stressTest() = StressOptions().check(this::class) // the magic button

    //@Test // TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
}