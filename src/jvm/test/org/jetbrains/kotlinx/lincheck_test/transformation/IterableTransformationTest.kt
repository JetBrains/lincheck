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
import org.jetbrains.kotlinx.lincheck.LincheckOptionsImpl
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

/**
 * Tests that java.lang.Iterable is transformed and
 * iterator() method returns transformed java.util.Iterator
 */
class IterableTransformationTest : VerifierState() {
    private var sum = 0

    @Operation
    fun operation() {
        val iterable: Iterable<Int> = listOf(1, 2, 3)
        for (i in iterable) {
            sum += i
        }
    }

    @Test
    fun test() {
        LincheckOptions {
            testingTimeInSeconds = 1
        }.check(this::class)
    }

    override fun extractState(): Any = 0 // constant state
}
