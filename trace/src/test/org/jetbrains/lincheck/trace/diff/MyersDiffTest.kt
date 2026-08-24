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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the Myers diff over plain string lists:
 * every script must reconstruct `right` from `left`,
 * and the number of edits must be minimal.
 */
class MyersDiffTest {

    private fun diffed(left: List<String>, right: List<String>): List<DiffLine> =
        diffLists(left = left, right = right) { l, r -> l == r }

    /** Applies [diff] to [left] and checks it produces [right] with the expected number of edits. */
    private fun assertDiff(left: List<String>, right: List<String>, expectedEdits: Int) {
        val diff = diffed(left, right)
        val reconstructed = mutableListOf<String>()
        var edits = 0
        diff.forEach { line ->
            when (line) {
                is UnchangedDiffLine -> {
                    assertEquals(left[line.leftIdx], right[line.rightIdx])
                    reconstructed.add(right[line.rightIdx])
                }
                is RemovedDiffLine -> edits++
                is AddedDiffLine -> {
                    reconstructed.add(right[line.rightIdx])
                    edits++
                }
            }
        }
        assertEquals(right, reconstructed)
        assertEquals(expectedEdits, edits)
    }

    @Test
    fun `identical lists are all unchanged`() {
        val diff = diffed(listOf("a", "b", "c"), listOf("a", "b", "c"))
        assertEquals(
            listOf(UnchangedDiffLine(0, 0), UnchangedDiffLine(1, 1), UnchangedDiffLine(2, 2)),
            diff,
        )
    }

    @Test
    fun `both lists empty`() {
        assertEquals(emptyList<DiffLine>(), diffed(emptyList(), emptyList()))
    }

    @Test
    fun `insertion in the middle`() = assertDiff(listOf("a", "c"), listOf("a", "b", "c"), expectedEdits = 1)

    @Test
    fun `deletion in the middle`() = assertDiff(listOf("a", "b", "c"), listOf("a", "c"), expectedEdits = 1)

    @Test
    fun `replacement is one removal plus one addition`() =
        assertDiff(listOf("a", "b", "c"), listOf("a", "x", "c"), expectedEdits = 2)

    @Test
    fun `left list empty`() = assertDiff(emptyList(), listOf("a", "b"), expectedEdits = 2)

    @Test
    fun `right list empty`() = assertDiff(listOf("a", "b"), emptyList(), expectedEdits = 2)

    @Test
    fun `completely different lists`() = assertDiff(listOf("a", "b"), listOf("x", "y", "z"), expectedEdits = 5)

    @Test
    fun `size arguments truncate the lists`() {
        val diff = diffLists(
            left = listOf("a", "b", "junk"), leftSize = 2,
            right = listOf("a", "b", "garbage"), rightSize = 2,
        ) { l, r -> l == r }
        assertEquals(listOf(UnchangedDiffLine(0, 0), UnchangedDiffLine(1, 1)), diff)
    }
}
