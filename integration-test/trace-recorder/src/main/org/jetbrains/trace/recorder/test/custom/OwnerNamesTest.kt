/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test.custom

import org.junit.Test

class A(var field: Int)

class OwnerNamesTest {
    val indexField = 0
    var aField: A? = null

    @Test
    fun testArrays() {
        val indexVariable = 1
        val arr = IntArray(3)
        arr[indexField] = 10
        arr[indexVariable] = -10
        arr[1] = -11

        val r1 = arr[indexField]
        val r2 = arr[indexVariable]
        val r3 = arr[1]
    }

    @Test
    fun testLocalVariables() {
        val l1: Int = 10
        val l2: A = A(10)

        aField = l2
        aField!!.field = l1 * 2
    }

    @Test
    fun testFields() {
        val a = A(10)
        a.field += 10
        val r = a.field - 10
    }
}