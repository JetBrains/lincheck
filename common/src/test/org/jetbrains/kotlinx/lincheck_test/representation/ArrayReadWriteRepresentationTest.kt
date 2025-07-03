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

/**
 * Check an array read and write operation representation.
 */
class ArrayReadWriteRepresentationTest : BaseTraceRepresentationTest("array_read_write") {

    private val array = IntArray(2)

    override fun operation() {
        val value = array[0]
        array[0] = value + 1
        array[1] = 0
    }

}