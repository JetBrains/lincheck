/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

/**
 * This test checks that managed strategies do not try to switch
 * thread context at reads and writes of local objects.
 * In case a strategy does not have this optimization,
 * this test fails by timeout since the number of
 * invocations is set to [Int.MAX_VALUE].
 */
@ModelCheckingCTest(actorsBefore = 0, actorsAfter = 0, actorsPerThread = 50, invocationsPerIteration = Int.MAX_VALUE, iterations = 50)
class LocalObjectEliminationTest : VerifierState() {
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
        return (a.any as A).array.sum()
    }

    @Test(timeout = 100_000)
    fun test() {
        LinChecker.check(this::class.java)
    }

    override fun extractState(): Any = 0 // constant state

    private data class A(var value: Int, var any: Any, val array: IntArray)
}
