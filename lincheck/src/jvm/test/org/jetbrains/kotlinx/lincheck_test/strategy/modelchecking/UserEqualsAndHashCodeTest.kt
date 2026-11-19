/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking

import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Checks that the analysis does not call `equals` and `hashCode` of the objects of the tested code.
 *
 * These methods are code of the tested program: they can have side effects, take locks, or throw.
 * The operations of this test never call them, so the analysis must not call them either.
 */
class UserEqualsAndHashCodeTest {
    private val counter = Counter()

    @Operation
    fun incrementAndGet(): Int = counter.incrementAndGet()

    @Operation
    fun get(): Int = counter.get()

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .configure()
        .check(this::class)

    @Test
    fun experimentalModelCheckingTest() = ModelCheckingOptions()
        .useExperimentalModelChecking()
        .configure()
        .check(this::class)

    private fun ModelCheckingOptions.configure() = this
        .iterations(10)
        .invocationsPerIteration(500)

    private class Counter {
        private val value = AtomicInteger()

        fun incrementAndGet(): Int = value.incrementAndGet()

        fun get(): Int = value.get()

        override fun equals(other: Any?): Boolean =
            error("equals() of an object of the tested code was called")

        override fun hashCode(): Int =
            error("hashCode() of an object of the tested code was called")
    }
}
