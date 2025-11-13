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

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.junit.Test

/**
 * Tests that java.lang.Iterable is transformed and
 * iterator() method returns transformed java.util.Iterator
 */
class IterableTransformationTest {
    private var sum = 0

    @Operation
    fun operation() {
        val iterable: Iterable<Int> = listOf(1, 2, 3)
        for (i in iterable) {
            sum += i
        }
    }

    @Test
    fun test() = ModelCheckingOptions()
        .iterations(1)
        .actorsBefore(1)
        .actorsAfter(1)
        .actorsPerThread(1)
        .check(this::class)
}
