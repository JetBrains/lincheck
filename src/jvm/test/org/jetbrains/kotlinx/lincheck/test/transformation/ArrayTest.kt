/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test


/**
 * Tests that array accesses are properly transformed and tracked.
 */
@ModelCheckingCTest(iterations = 1, actorsBefore = 0, actorsAfter = 0, actorsPerThread = 1, threads = 1)
class ArrayAccessTest : VerifierState() {
    private var array = Array<Int>(3) { 0 }

    @Operation
    fun operation() {
        array[0] = 0
        array[1] = 1
        array[2] = 2
        check(array[0] == 0)
        check(array[1] == 1)
        check(array[2] == 2)
    }

    @Test
    fun test() {
        LinChecker.check(this::class.java)
    }

    override fun extractState(): Any = array
}

/**
 * Tests that multidimensional array accesses are properly transformed and tracked.
 */
@ModelCheckingCTest(iterations = 1, actorsBefore = 0, actorsAfter = 0, actorsPerThread = 1, threads = 1)
class MultiDimensionalArrayAccessTest : VerifierState() {
    private var array = Array<Array<Int>>(2) { Array(2) { 0 } }

    @Operation
    fun operation() {
        array[0][0] = 0
        array[0][1] = 1
        array[1][0] = 2
        array[1][1] = 3
        check(array[0][0] == 0)
        check(array[0][1] == 1)
        check(array[1][0] == 2)
        check(array[1][1] == 3)
    }

    @Test
    fun test() {
        LinChecker.check(this::class.java)
    }

    override fun extractState(): Any = array
}