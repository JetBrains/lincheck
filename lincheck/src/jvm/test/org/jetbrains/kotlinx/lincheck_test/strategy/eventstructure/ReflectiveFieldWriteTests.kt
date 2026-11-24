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
import kotlin.concurrent.thread

/**
 * Checks that a field write performed through `java.lang.reflect.Field.set*` is visible to the memory tracker:
 * without routing it through `memoryTracker.beforeWrite`, a later read of the same field returns a stale,
 * pre-write value, because the experimental strategy overrides plain-field-read results with its own model
 * of the location's value (see [org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy.beforeReadField]).
 */
class ReflectiveFieldWriteTests {

    private class Cell(@JvmField var x: Int)

    private object StaticCell {
        @JvmField var x: Int = 0
    }

    @Test
    fun testFieldSetIsVisibleToLaterRead() {
        for (memoryModel in MemoryModel.entries) {
            litmusTest(assertSame(setOf(2)), memoryModel) {
                val cell = Cell(1)
                val field = Cell::class.java.getDeclaredField("x").apply { isAccessible = true }
                field.set(cell, 2)
                cell.x
            }
        }
    }

    @Test
    fun testFieldSetIntIsVisibleToLaterRead() {
        for (memoryModel in MemoryModel.entries) {
            litmusTest(assertSame(setOf(2)), memoryModel) {
                val cell = Cell(1)
                val field = Cell::class.java.getDeclaredField("x").apply { isAccessible = true }
                field.setInt(cell, 2)
                cell.x
            }
        }
    }

    @Test
    fun testStaticFieldSetIsVisibleToLaterRead() {
        for (memoryModel in MemoryModel.entries) {
            litmusTest(assertSame(setOf(2)), memoryModel) {
                StaticCell.x = 1
                val field = StaticCell::class.java.getDeclaredField("x").apply { isAccessible = true }
                field.set(null, 2)
                StaticCell.x
            }
        }
    }

    @Test
    fun testFieldSetWriteIsVisibleToOtherThread() {
        for (memoryModel in MemoryModel.entries) {
            litmusTest(assertSame(setOf(2)), memoryModel, requireExhaustion = true) {
                val cell = Cell(1)
                val field = Cell::class.java.getDeclaredField("x").apply { isAccessible = true }
                var result = -1
                val writer = thread { field.set(cell, 2) }
                writer.join()
                val reader = thread { result = cell.x }
                reader.join()
                result
            }
        }
    }
}
