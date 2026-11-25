/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.representation

/**
 * Checks the trace of array accesses that throw `ArrayIndexOutOfBoundsException`:
 * such an access reads or writes nothing, so the trace does not show it.
 */
class ArrayIndexOutOfBoundsRepresentationTest : BaseTraceRepresentationTest("array_index_out_of_bounds") {

    private val array = IntArray(2)

    override fun operation() {
        try {
            array[2] = 1
        } catch (_: ArrayIndexOutOfBoundsException) {
            array[0] = 1
        }
        try {
            array[1] = array[-1]
        } catch (_: ArrayIndexOutOfBoundsException) {
            array[1] = 2
        }
    }
}
