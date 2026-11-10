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
import org.jetbrains.lincheck.util.UnsafeHolder
import org.jetbrains.lincheck.util.getFieldOffsetViaUnsafe
import org.junit.Test
import kotlin.concurrent.thread

/** Litmus tests for safe and unsafe publication semantics and final-field semantics. */
class PublicationTests {

    @Test
    fun finalPrimitiveReadThroughUnsafe() = finalPrimitive(leaksThis = false, readViaUnsafe = true)

    @Test
    fun finalPrimitiveReadThroughUnsafeWithLeak() = finalPrimitive(leaksThis = true, readViaUnsafe = true)

    @Test
    fun finalPrimitiveInAllMemoryModels() {
        for (memoryModel in MemoryModel.entries) {
            finalPrimitive(leaksThis = false, memoryModel = memoryModel)
            finalPrimitive(leaksThis = true, memoryModel = memoryModel)
            finalPrimitive(leaksThis = false, readViaUnsafe = true, memoryModel = memoryModel)
            finalPrimitive(leaksThis = true, readViaUnsafe = true, memoryModel = memoryModel)
        }
    }

    private fun finalPrimitive(
        leaksThis: Boolean,
        readViaUnsafe: Boolean = false,
        memoryModel: MemoryModel = MemoryModel.RelaxedJava,
    ) {
        val outcomes = if (leaksThis) setOf(-1, 0, 42) else setOf(-1, 42)
        litmusTest(assertSame(outcomes, UNKNOWN), memoryModel, requireExhaustion = true) {
            var published: FinalPrimitive? = null
            var result = -1
            val writer = thread {
                published = FinalPrimitive(42) { if (leaksThis) published = it }
            }
            val reader = thread {
                val observed = published
                result = when {
                    observed == null -> -1
                    readViaUnsafe -> observed.readViaUnsafe()
                    else -> observed.field
                }
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun mutablePrimitiveReadThroughUnsafe() = mutablePrimitive(leaksThis = false, readViaUnsafe = true)

    private fun mutablePrimitive(leaksThis: Boolean, readViaUnsafe: Boolean = false) {
        litmusTest(assertSame(setOf(-1, 0, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: MutablePrimitive? = null
            var result = -1
            val writer = thread {
                published = MutablePrimitive(42) { if (leaksThis) published = it }
            }
            val reader = thread {
                val observed = published
                result = if (readViaUnsafe) observed?.readViaUnsafe() ?: -1 else observed?.field ?: -1
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun finalReferenceReadThroughUnsafe() = finalReference(leaksThis = false, readViaUnsafe = true)

    @Test
    fun finalReferenceReadThroughUnsafeWithLeak() = finalReference(leaksThis = true, readViaUnsafe = true)

    private fun finalReference(leaksThis: Boolean, readViaUnsafe: Boolean = false) {
        val outcomes = if (leaksThis) setOf(-1, 0, 1) else setOf(-1, 1)
        litmusTest(assertSame(outcomes, UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: FinalReference? = null
            var result = -1
            val writer = thread {
                published = FinalReference(Any()) { if (leaksThis) published = it }
            }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else {
                    val value = if (readViaUnsafe) observed.readViaUnsafe() else observed.field
                    if (value == null) 0 else 1
                }
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun finalReferenceReadThroughUnsafeProtectsMutableField() = finalReferenceProtectsMutableField(readViaUnsafe = true)

    private fun finalReferenceProtectsMutableField(readViaUnsafe: Boolean) {
        litmusTest(assertSame(setOf(-1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: FinalCellReference? = null
            var result = -1
            val writer = thread { published = FinalCellReference(Cell(42)) }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else {
                    val cell = if (readViaUnsafe) observed.readViaUnsafe() else observed.cell
                    cell?.value ?: -2
                }
            }
            writer.join()
            reader.join()
            result
        }
    }

    private class FinalPrimitive(value: Int, publish: (FinalPrimitive) -> Unit) {
        companion object {
            private val fieldOffset = getFieldOffsetViaUnsafe(FinalPrimitive::class.java.getDeclaredField("field"))
        }

        val field: Int

        init {
            publish(this)
            field = value
        }

        fun readViaUnsafe(): Int = UnsafeHolder.UNSAFE.getInt(this, fieldOffset)
    }

    private class MutablePrimitive(value: Int, publish: (MutablePrimitive) -> Unit) {
        companion object {
            private val fieldOffset = getFieldOffsetViaUnsafe(MutablePrimitive::class.java.getDeclaredField("field"))
        }

        var field: Int

        init {
            publish(this)
            field = value
        }

        fun readViaUnsafe(): Int = UnsafeHolder.UNSAFE.getInt(this, fieldOffset)
    }

    private class FinalReference(value: Any, publish: (FinalReference) -> Unit) {
        companion object {
            private val fieldOffset = getFieldOffsetViaUnsafe(FinalReference::class.java.getDeclaredField("field"))
        }

        val field: Any?

        init {
            publish(this)
            field = value
        }

        fun readViaUnsafe(): Any? = UnsafeHolder.UNSAFE.getObject(this, fieldOffset)
    }

    // Private properties let Kotlin keep distinct backing fields with the same names in the hierarchy.

    private class Cell(var value: Int)

    private class FinalCellReference(val cell: Cell?) {
        companion object {
            private val cellOffset = getFieldOffsetViaUnsafe(FinalCellReference::class.java.getDeclaredField("cell"))
        }

        fun readViaUnsafe(): Cell? = UnsafeHolder.UNSAFE.getObject(this, cellOffset) as Cell?
    }

}
