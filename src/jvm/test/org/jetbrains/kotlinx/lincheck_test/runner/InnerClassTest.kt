/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.runner

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.lincheck.datastructures.StressOptions
import org.junit.Test

class OuterClass {
    inner class InnerClass {
        @Operation
        fun zero() = 0
    }

    class NestedClass {
        @Operation
        fun zero() = 0
    }
}

class InnerClassTest {
    @Test
    fun testInnerClass() {
        StressOptions()
            .invocationsPerIteration(1)
            .iterations(1)
            .check(OuterClass.InnerClass::class)
    }

    @Test
    fun testNestedClass() {
        StressOptions()
            .invocationsPerIteration(1)
            .iterations(1)
            .check(OuterClass.NestedClass::class)
    }
}
