/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest
import org.junit.Test

@ModelCheckingCTest(
    actorsBefore = 0,
    actorsAfter = 0,
    actorsPerThread = 50,
    invocationsPerIteration = Int.MAX_VALUE,
    iterations = 50
)
class A {
    private data class A(var value: Int, var any: Any, val array: IntArray)
    
    @Operation
    fun f(): Int {
        val a = A(0, this, IntArray(10))
        return a.array.sum()
    }

    @Test(timeout = 100_000)
    fun test() {
        LinChecker.check(this::class.java)
    }
}