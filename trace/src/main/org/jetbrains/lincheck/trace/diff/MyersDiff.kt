/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.diff

sealed class DiffLine()
data class UnchangedDiffLine(val leftIdx: Int, val rightIdx: Int): DiffLine()
data class RemovedDiffLine(val leftIdx: Int): DiffLine()
data class AddedDiffLine(val rightIdx: Int): DiffLine()

/**
 * Calculates the diff between two lists and return diff property.
 *
 * This implementation uses Myers' diff algorithm.
 */
internal fun <T> diffLists(left: List<T>, right: List<T>, equals: (T, T) -> Boolean): List<DiffLine> {
    val n = left.size
    val m = right.size
    if (n == 0 && m == 0) return emptyList()

    val max = n + m
    val v = IntArray(2 * max + 1)
    val trace = mutableListOf<IntArray>()

    v[max + 1] = 0

    var x: Int
    var y: Int
    outer@ for (d in 0..max) {
        val currentV = v.copyOf()
        trace.add(currentV)
        for (k in -d..d step 2) {
            val idx = k + max
            x = if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                v[idx + 1]
            } else {
                v[idx - 1] + 1
            }
            y = x - k
            while (x < n && y < m && equals(left[x], right[y])) {
                x++
                y++
            }
            v[idx] = x
            if (x >= n && y >= m) {
                break@outer
            }
        }
    }

    val result = mutableListOf<DiffLine>()
    x = n
    y = m

    for (d in trace.size - 1 downTo 0) {
        val k = x - y
        val idx = k + max
        val currentV = trace[d]

        val prevK = if (k == -d || (k != d && currentV[idx - 1] < currentV[idx + 1])) {
            k + 1
        } else {
            k - 1
        }
        val prevX = currentV[prevK + max]
        val prevY = prevX - prevK

        while (x > prevX && y > prevY) {
            result.add(UnchangedDiffLine(x - 1, y - 1))
            x--
            y--
        }

        if (d > 0) {
            if (x > prevX) {
                result.add(RemovedDiffLine(x - 1))
                x--
            } else if (y > prevY) {
                result.add(AddedDiffLine(y - 1))
                y--
            }
        }
    }

    return result.reversed()
}
