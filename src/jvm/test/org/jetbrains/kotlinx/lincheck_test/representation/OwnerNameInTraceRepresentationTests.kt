/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

class OwnerNameInTraceRepresentationTest : BaseTraceRepresentationTest("owner_name_in_trace") {

    @Volatile
    private var value: Int = 1

    @Volatile
    private var stub = StubClass()
    private val array: Array<Int> = arrayOf(0)


    override fun operation() {
        readWriteTestMethod()
        val result = stub
        result.readWriteTestMethod()
        doReadWriteWithOtherObject()
    }

    private fun readWriteTestMethod(): Int {
        value = 2
        array[0] = 4
        return value + array[0]
    }

    private fun doReadWriteWithOtherObject(): Int {
        stub.value = 2
        stub.array[0] = 4
        return stub.value + stub.array[0]
    }

    class StubClass {
        @Volatile
        @JvmField
        var value: Int = 1
        val array: Array<Int> = arrayOf(1)

        fun readWriteTestMethod(): Int {
            value = 3
            array[0] = 4
            return value + array[0]
        }
    }
}

class ArrayNameInTraceRepresentationTest : BaseTraceRepresentationTest("array_name_in_trace") {

    private var intArray: IntArray = intArrayOf(1)
    private var shortArray: ShortArray = shortArrayOf(1)
    private var charArray: CharArray = charArrayOf('1')
    private var byteArray: ByteArray = byteArrayOf(1)
    private var booleanArray: BooleanArray = booleanArrayOf(true)

    private val floatArray: FloatArray = floatArrayOf(1.0f)
    private val doubleArray: DoubleArray = doubleArrayOf(1.0)
    private val longArray: LongArray = longArrayOf(1L)

    override fun operation() {
        readActions()
        writeActions()
    }

    private fun readActions() {
        intArray[0]
        shortArray[0]
        charArray[0]
        byteArray[0]
        booleanArray[0]

        floatArray[0]
        doubleArray[0]
        longArray[0]
    }

    private fun writeActions() {
        intArray[0] = 0
        shortArray[0] = 0
        charArray[0] = '0'
        byteArray[0] = 0
        booleanArray[0] = false

        floatArray[0] = 0f
        doubleArray[0] = 0.0
        longArray[0] = 0L
    }

}