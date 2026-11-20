/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure

import org.junit.Test
import java.util.Arrays
import kotlin.concurrent.thread

/**
 * Checks that the experimental strategy sees the array writes of [Arrays.fill].
 *
 * Ring buffers and hash tables clear their arrays with `Arrays.fill`;
 * if the writes stay unknown to the strategy, every later read returns the old contents.
 */
class ArraysFillTest {

    @Test
    fun testFillIsVisibleToLaterReads() {
        litmusTest(assertSame(setOf(listOf(0, 0, 0)))) {
            val array = IntArray(3)
            for (i in array.indices) {
                array[i] = i + 1
            }
            Arrays.fill(array, 0)
            listOf(array[0], array[1], array[2])
        }
    }

    @Test
    fun testFillOfRangeIsVisibleToLaterReads() {
        litmusTest(assertSame(setOf(listOf(1L, 7L, 7L, 4L)))) {
            val array = LongArray(4)
            for (i in array.indices) {
                array[i] = i + 1L
            }
            Arrays.fill(array, 1, 3, 7L)
            listOf(array[0], array[1], array[2], array[3])
        }
    }

    @Test
    fun testFillWithNullIsVisibleToLaterReads() {
        litmusTest(assertSame(setOf(listOf<String?>(null, null)))) {
            val array = arrayOfNulls<String>(2)
            array[0] = "a"
            array[1] = "b"
            Arrays.fill(array, null)
            listOf(array[0], array[1])
        }
    }

    @Test
    fun testFillRacingWithReads() {
        // `fill` writes the elements in index order, so a reader that sees the new value
        // at index 1 cannot see the old value at index 0 afterwards.
        val outcomes = setOf(
            listOf(0, 0),
            listOf(0, 7),
            listOf(7, 7),
        )
        litmusTest(assertSame(outcomes)) {
            val array = IntArray(2)
            val result = IntArray(2)
            val t1 = thread {
                Arrays.fill(array, 7)
            }
            val t2 = thread {
                result[1] = array[1]
                result[0] = array[0]
            }
            t1.join()
            t2.join()
            listOf(result[1], result[0])
        }
    }
}
