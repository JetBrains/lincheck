/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.strategy.eventstructure.litmus

import org.jetbrains.kotlinx.lincheck.detectFailure
import org.jetbrains.lincheck.IncorrectResultsFailure
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.Assert.assertTrue
import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import org.jetbrains.lincheck_test.strategy.eventstructure.assertSame
import org.jetbrains.lincheck_test.strategy.eventstructure.litmusTest
import org.junit.Test
import kotlin.concurrent.thread

class ReflectiveFieldAccessTest {
    class Counter {
        private var value = 0

        @Operation
        fun increment(): Int {
            val field = Counter::class.java.getDeclaredField("value").apply { isAccessible = true }
            val old = field.getInt(this)
            field.setInt(this, old + 1)
            return old
        }
    }

    @Test
    fun schedulingFindsLostUpdate() {
        val failure = ModelCheckingOptions()
            .iterations(0)
            .addCustomScenario {
                parallel {
                    thread { actor(Counter::increment) }
                    thread { actor(Counter::increment) }
                }
            }
            .detectFailure(Counter::class.java)
        assertTrue(failure.toString(), failure is IncorrectResultsFailure)
    }

    private class Cell {
        @JvmField var x = 0
        @JvmField var y = 0
        @JvmField @Volatile var volatileX = 0
        @JvmField @Volatile var volatileY = 0
        @JvmField var floatValue = 0f
        @JvmField var doubleValue = 0.0
        @JvmField var reference: Any? = null
    }

    private object StaticCell {
        @JvmField var value = 0
    }

    @Test
    fun plainReadsExploreStoreBuffering() = storeBuffering(volatile = false, widened = false)

    @Test
    fun volatileReadsForbidStoreBuffering() = storeBuffering(volatile = true, widened = false)

    @Test
    fun widenedReadsExploreStoreBuffering() = storeBuffering(volatile = false, widened = true)

    private fun storeBuffering(volatile: Boolean, widened: Boolean) {
        for (model in MemoryModel.entries) {
            // The release-acquire model deliberately weakens even volatile accesses.
            val expected = if (model == MemoryModel.SequentialConsistency || volatile && model == MemoryModel.RelaxedJava) {
                setOf(0 to 1, 1 to 0, 1 to 1)
            } else {
                setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)
            }
            litmusTest(assertSame(expected), model, requireExhaustion = true) {
                val cell = Cell()
                val x = Cell::class.java.getDeclaredField(if (volatile) "volatileX" else "x")
                    .apply { isAccessible = true }
                val y = Cell::class.java.getDeclaredField(if (volatile) "volatileY" else "y")
                    .apply { isAccessible = true }
                var first = -1
                var second = -1
                val a = thread {
                    x.setInt(cell, 1)
                    first = if (widened) y.getLong(cell).toInt() else y.getInt(cell)
                }
                val b = thread {
                    y.set(cell, 1)
                    second = if (widened) x.getDouble(cell).toInt() else x.get(cell) as Int
                }
                a.join()
                b.join()
                first to second
            }
        }
    }

    @Test
    fun writePreservesExternalObjectsInitialValue() {
        for (model in MemoryModel.entries) {
            val cell = Cell()
            litmusTest(assertSame(setOf(0, 1)), model, requireExhaustion = true) {
                val field = Cell::class.java.getDeclaredField("x").apply { isAccessible = true }
                var result = -1
                val writer = thread { field.setInt(cell, 1) }
                val reader = thread { result = field.getInt(cell) }
                writer.join()
                reader.join()
                result
            }
        }
    }

    @Test
    fun primitiveWidening() {
        for (model in MemoryModel.entries) {
            litmusTest(assertSame(setOf(listOf(2, 3f, 65.0))), model) {
                val cell = Cell()
                Cell::class.java.getDeclaredField("x").apply { isAccessible = true }.setByte(cell, 2)
                Cell::class.java.getDeclaredField("floatValue").apply { isAccessible = true }.set(cell, 3.toShort())
                Cell::class.java.getDeclaredField("doubleValue").apply { isAccessible = true }.setChar(cell, 'A')
                listOf(cell.x, cell.floatValue, cell.doubleValue)
            }
        }
    }

    @Test
    fun staticAccessIgnoresReceiver() {
        for (model in MemoryModel.entries) {
            litmusTest(assertSame(setOf(2L)), model) {
                val field = StaticCell::class.java.getDeclaredField("value").apply { isAccessible = true }
                field.set(Any(), 2)
                field.getLong(Any())
            }
        }
    }

    @Test
    fun referenceWritePublishesObject() {
        for (model in MemoryModel.entries) {
            litmusTest(assertSame(setOf(42)), model) {
                val cell = Cell()
                val field = Cell::class.java.getDeclaredField("reference").apply { isAccessible = true }
                val writer = thread { field.set(cell, Cell().apply { x = 42 }) }
                writer.join()
                (field.get(cell) as Cell).x
            }
        }
    }

    @Test
    fun failedAccessesPreserveExceptionsAndMemory() {
        for (model in MemoryModel.entries) {
            litmusTest(assertSame(setOf(4 to 0)), model) {
                val cell = Cell()
                val field = Cell::class.java.getDeclaredField("x").apply { isAccessible = true }
                var failures = 0
                try { field.set(cell, "invalid") } catch (_: IllegalArgumentException) { failures++ }
                try { field.setLong(cell, 1L) } catch (_: IllegalArgumentException) { failures++ }
                try { field.getBoolean(cell) } catch (_: IllegalArgumentException) { failures++ }
                try { field.getInt(null) } catch (_: NullPointerException) { failures++ }
                failures to cell.x
            }
        }
    }
}
