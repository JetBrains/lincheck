/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.ObjectToObjectWeakHashMap
import org.junit.Assert.assertEquals
import org.junit.Test

class ObjectToObjectWeakHashMapTest {

    /**
     * Checks that map don't persist keys with no strong references.
     *
     * Here supposed that if this map is a regular [HashMap] - this test will fail.
     */
    @Test
    fun `should not fail with out of memory error`() {
        val map = ObjectToObjectWeakHashMap<Int, List<Int>>()
        for (i in 0 until 10_000) {
            map[i] = List(10_000) { it }
        }
    }

    @Test
    fun `should have the same behaviour as a regular hash map`() {
        val list1 = listOf(1, 2, 3)
        val list2 = listOf(1, 2, 3)
        val list3 = listOf(1, 2, 3)

        val map = ObjectToObjectWeakHashMap<Int, List<Int>>()
        map[1] = list1
        map[2] = list2
        map[3] = list3
        map.computeIfAbsent(4) { listOf(4, 5, 6) }

        assertEquals(map[1], list1)
        assertEquals(map[2], list2)
        assertEquals(map[3], list3)
        assertEquals(map[4], listOf(4, 5, 6))
    }

}