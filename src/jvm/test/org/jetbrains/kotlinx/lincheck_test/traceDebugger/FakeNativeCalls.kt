/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.traceDebugger

object FakeNativeCalls {
    @JvmStatic
    fun makeNewByteArray() = ByteArray(10) { it.toByte() }

    @JvmStatic
    fun init(byteArray: ByteArray) {
        for (i in byteArray.indices) {
            byteArray[i] = i.toByte()
        }
    }

    @JvmStatic
    fun fail(): Nothing = error("fail")

    @JvmStatic
    fun failingInit(byteArray: ByteArray) {
        for (i in 0..<byteArray.size / 2) {
            byteArray[i] = i.toByte()
        }
        error("fail")
    }

    @JvmStatic
    fun id(byteArray: ByteArray): ByteArray = byteArray

    @JvmStatic
    fun saveOrIsSaved(byteArray: ByteArray): Boolean {
        if (savedArray == null) {
            savedArray = byteArray
        }
        return savedArray === byteArray
    }
    private var savedArray: ByteArray? = null
}
