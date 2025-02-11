/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.guide

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.jupiter.api.*
import kotlin.test.assertFailsWith

class Counter {
    @Volatile
    private var value = 0

    fun inc(): Int = ++value
    fun get() = value
}

class BasicCounterTest {
    private val c = Counter() // initial state

    // operations on the Counter
    @Operation
    fun inc() = c.inc()

    @Operation
    fun get() = c.get()

    @Test
    fun stressTest() {
        @Suppress("UNUSED_VARIABLE")
        val error = assertFailsWith<AssertionError> { StressOptions().check(this::class) /* the magic button */ }
        //throw error // TODO: Please, uncomment me to run the test and get the output
    }

    @Test
    fun modelCheckingTest() {
        @Suppress("UNUSED_VARIABLE")
        val error = assertFailsWith<AssertionError> { ModelCheckingOptions().check(this::class) }
        //throw error // TODO: Please, uncomment me to run the test and get the output
    }
}