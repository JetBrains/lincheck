/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.util.isInTraceDebuggerMode
import java.util.concurrent.atomic.*
import org.junit.Assume.assumeFalse
import org.junit.*


class Striped64SupportTest {
    @Before
    fun setUp() {
        assumeFalse(isInTraceDebuggerMode) // requires invocationsPerIteration(1), but then does not fail in this case
    }

    val counter = LongAdder() // LongAdder uses Striped64.getProbe() under the hood

    @Operation
    fun inc() = counter.increment()

    @Operation
    fun dec() = counter.decrement()

    @Operation
    fun sum() = counter.sum()

    @Test
    fun test() {
        val failure = ModelCheckingOptions()
            .minimizeFailedScenario(false)
            .checkImpl(this::class.java)
        assert(failure is IncorrectResultsFailure) {
            "This test should fail with IncorrectResultsFailure, but another error has been detected:\n$failure"
        }
    }
}