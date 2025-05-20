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

package org.jetbrains.kotlinx.lincheck_test.transformation

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest

abstract class AbstractArrayAccessTest : AbstractLincheckTest() {

    @Operation
    abstract fun operation()

    override fun <O : Options<O, *>> O.customize() {
        iterations(0)
        .addCustomScenario {
            parallel {
                thread { actor(::operation) }
            }
        }
    }
}

/**
 * Tests that int array accesses are properly transformed and tracked.
 */
class IntArrayAccessTest : AbstractArrayAccessTest() {
    private var array = IntArray(3) { 0 }

    override fun operation() {
        array[0] = 0
        array[1] = 1
        array[2] = 2
        check(array[0] == 0)
        check(array[1] == 1)
        check(array[2] == 2)
    }
}

/**
 * Tests that byte array accesses are properly transformed and tracked.
 */
class ByteArrayAccessTest : AbstractArrayAccessTest() {
    private var array = ByteArray(3) { 0 }

    override fun operation() {
        array[0] = 0
        array[1] = 1
        array[2] = 2
        check(array[0] == 0.toByte())
        check(array[1] == 1.toByte())
        check(array[2] == 2.toByte())
    }
}

/**
 * Tests that byte array accesses are properly transformed and tracked.
 */
class ShortArrayAccessTest : AbstractArrayAccessTest() {
    private var array = ShortArray(3) { 0 }

    override fun operation() {
        array[0] = 0
        array[1] = 1
        array[2] = 2
        check(array[0] == 0.toShort())
        check(array[1] == 1.toShort())
        check(array[2] == 2.toShort())
    }
}

/**
 * Tests that long array accesses are properly transformed and tracked.
 */
class LongArrayAccessTest : AbstractArrayAccessTest() {
    private var array = LongArray(3) { 0 }

    override fun operation() {
        array[0] = 0
        array[1] = 1
        array[2] = 2
        check(array[0] == 0L)
        check(array[1] == 1L)
        check(array[2] == 2L)
    }
}

class CharArrayAccessTest : AbstractArrayAccessTest() {
    private var array = CharArray(3) { 0.toChar() }

    override fun operation() {
        array[0] = 'a'
        array[1] = 'b'
        array[2] = 'c'
        check(array[0] == 'a')
        check(array[1] == 'b')
        check(array[2] == 'c')
    }
}

class BooleanArrayAccessTest : AbstractArrayAccessTest() {
    private var array = BooleanArray(3) { false }

    override fun operation() {
        array[0] = false
        array[1] = true
        check(array[0] == false)
        check(array[1] == true)
    }
}

/**
 * Tests that boxed array accesses are properly transformed and tracked.
 */
class BoxedArrayAccessTest : AbstractArrayAccessTest() {
    private var array = Array<Int>(3) { 0 }

    override fun operation() {
        array[0] = 0
        array[1] = 1
        array[2] = 2
        check(array[0] == 0)
        check(array[1] == 1)
        check(array[2] == 2)
    }
}

/**
 * Tests that multidimensional array accesses are properly transformed and tracked.
 */
class MultiDimensionalArrayAccessTest : AbstractArrayAccessTest() {
    private var array = Array<Array<Int>>(2) { Array(2) { 0 } }

    override fun operation() {
        array[0][0] = 0
        array[0][1] = 1
        array[1][0] = 2
        array[1][1] = 3
        check(array[0][0] == 0)
        check(array[0][1] == 1)
        check(array[1][0] == 2)
        check(array[1][1] == 3)
    }
}