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
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

@ModelCheckingCTest(
    actorsBefore = 0,
    actorsAfter = 0,
    actorsPerThread = 50,
    invocationsPerIteration = Int.MAX_VALUE,
    iterations = 50
)
class Aa {
    @Before
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    @Operation
    fun operation(): Int {
        val a = A(0, this, IntArray(2))
        a.any = a
        repeat(20) {
            a.value = it
        }
        a.array[1] = 54
        val b = A(a.value, a.any, a.array)
        b.value = 65
        repeat(20) {
            b.array[0] = it
        }
        a.any = b
        // check that closure object and captured `x: IntRef` object
        // are correctly classified as local objects;
        // note that these classes itself are not instrumented,
        // but the creation of their instances still should be tracked
        var x = 0
        val closure = {
            a.value += 1
            x += 1
        }
        repeat(20) {
            closure()
        }
        return (a.any as A).array.sum()
    }

    @Test(timeout = 100_000)
    fun test() {
        LinChecker.check(this::class.java)
    }

    private data class A(var value: Int, var any: Any, val array: IntArray)
}