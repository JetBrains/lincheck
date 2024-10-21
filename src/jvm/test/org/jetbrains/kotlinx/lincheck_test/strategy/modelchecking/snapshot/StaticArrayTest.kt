/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking.snapshot

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.junit.After
import org.junit.Before
import kotlin.random.Random


private var intArray = intArrayOf(1, 2, 3)

class StaticIntArrayTest : SnapshotAbstractTest() {
    private var ref = intArray
    private var values = intArray.copyOf()

    @Operation
    fun modify() {
        intArray[0]++
    }

    @After
    fun checkStaticStateRestored() {
        check(intArray == ref)
        check(ref.contentEquals(values))
    }
}


private class X(var value: Int) {
    @OptIn(ExperimentalStdlibApi::class)
    override fun toString() = "X@${this.hashCode().toHexString()}($value)"
}

private var objArray = arrayOf<X>(X(1), X(2), X(3))

class StaticObjectArrayTest : SnapshotAbstractTest() {
    private var ref: Array<X>? = null
    private var elements: Array<X?>? = null
    private var values: Array<Int>? = null

    @Operation
    fun modify() {
        objArray[0].value++
        objArray[1].value--
        objArray[2] = X(Random.nextInt())
    }

    @Before
    fun saveInitStaticState() {
        ref = objArray

        elements = Array<X?>(3) { null }
        objArray.forEachIndexed { index, x -> elements!![index] = x }

        values = Array<Int>(3) { 0 }
        objArray.forEachIndexed { index, x -> values!![index] = x.value }
    }

    @After
    fun checkStaticStateRestored() {
        check(objArray == ref)
        check(objArray.contentEquals(elements))
        check(objArray.map { it.value }.toTypedArray().contentEquals(values))
    }
}