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

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.junit.Test

private class CloneableBox(var value: Int) : Cloneable {
    public override fun clone(): CloneableBox = super.clone() as CloneableBox
}

/**
 * Tests of `Object.clone()`.
 *
 * `clone()` produces its result with a native call, not a `NEW` instruction, so the checker only learns
 * about the clone when it is returned; a class whose `clone()` writes into the clone's own fields right
 * after cloning (`java.util.BitSet.clone()`, for one, does `result.words = words.clone()`) used to crash
 * the checker with a `NullPointerException` at the first such write, because the clone was not registered
 * as a known object yet.
 */
class ObjectCloneTests {

    @Test
    fun testCloneFieldWriteIsVisibleToLaterRead() {
        for (memoryModel in listOf(MemoryModel.SequentialConsistency, MemoryModel.RelaxedJava)) {
            litmusTest(assertSame(setOf(2)), memoryModel) {
                val original = CloneableBox(1)
                val clone = original.clone()
                clone.value = 2
                clone.value
            }
        }
    }

    @Test
    fun testCloneIsIndependentOfOriginal() {
        for (memoryModel in listOf(MemoryModel.SequentialConsistency, MemoryModel.RelaxedJava)) {
            litmusTest(assertSame(setOf(1)), memoryModel) {
                val original = CloneableBox(1)
                val clone = original.clone()
                clone.value = 2
                original.value
            }
        }
    }
}
