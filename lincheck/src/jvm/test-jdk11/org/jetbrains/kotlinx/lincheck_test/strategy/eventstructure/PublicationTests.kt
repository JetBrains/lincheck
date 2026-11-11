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
import org.jetbrains.lincheck.util.MemoryOrdering
import org.junit.Test
import java.lang.invoke.MethodHandles
import kotlin.concurrent.thread

/** Every read access mode of a VarHandle over a final field. */
private val READ_MODES = listOf(
    MemoryOrdering.PLAIN, MemoryOrdering.OPAQUE, MemoryOrdering.ACQUIRE, MemoryOrdering.VOLATILE,
)

/**
 * Litmus tests for final-field semantics of VarHandle reads in every read access mode.
 *
 * The direct field-read counterparts live in the `litmus` package;
 * here the reader goes through a VarHandle in the given [MemoryOrdering],
 * and the final-field guarantee must not depend on the mode.
 */
class PublicationTests {

    @Test
    fun finalPrimitiveGet() = finalPrimitive(MemoryOrdering.PLAIN, leaksThis = false)

    @Test
    fun finalPrimitiveGetOpaque() = finalPrimitive(MemoryOrdering.OPAQUE, leaksThis = false)

    @Test
    fun finalPrimitiveGetAcquire() = finalPrimitive(MemoryOrdering.ACQUIRE, leaksThis = false)

    @Test
    fun finalPrimitiveGetVolatile() = finalPrimitive(MemoryOrdering.VOLATILE, leaksThis = false)

    @Test
    fun finalPrimitiveGetWithLeak() = finalPrimitive(MemoryOrdering.PLAIN, leaksThis = true)

    @Test
    fun finalPrimitiveGetOpaqueWithLeak() = finalPrimitive(MemoryOrdering.OPAQUE, leaksThis = true)

    @Test
    fun finalPrimitiveGetAcquireWithLeak() = finalPrimitive(MemoryOrdering.ACQUIRE, leaksThis = true)

    @Test
    fun finalPrimitiveGetVolatileWithLeak() = finalPrimitive(MemoryOrdering.VOLATILE, leaksThis = true)

    @Test
    fun finalPrimitiveGetInAllMemoryModels() {
        for (memoryModel in MemoryModel.entries) {
            for (mode in READ_MODES) {
                finalPrimitive(mode, leaksThis = false, memoryModel = memoryModel)
                finalPrimitive(mode, leaksThis = true, memoryModel = memoryModel)
            }
        }
    }

    private fun finalPrimitive(
        mode: MemoryOrdering,
        leaksThis: Boolean,
        memoryModel: MemoryModel = MemoryModel.RelaxedJava,
    ) {
        // The final-field guarantee does not depend on the read mode; only a leaked `this` breaks it.
        val outcomes = if (leaksThis) setOf(-1, 0, 42) else setOf(-1, 42)
        litmusTest(assertSame(outcomes, UNKNOWN), memoryModel, requireExhaustion = true) {
            var published: FinalPrimitive? = null
            var result = -1
            val writer = thread {
                published = FinalPrimitive(42) { if (leaksThis) published = it }
            }
            val reader = thread {
                result = published?.read(mode) ?: -1
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun mutablePrimitiveGet() = mutablePrimitive(MemoryOrdering.PLAIN)

    @Test
    fun mutablePrimitiveGetOpaque() = mutablePrimitive(MemoryOrdering.OPAQUE)

    @Test
    fun mutablePrimitiveGetAcquire() = mutablePrimitive(MemoryOrdering.ACQUIRE)

    @Test
    fun mutablePrimitiveGetVolatile() = mutablePrimitive(MemoryOrdering.VOLATILE)

    private fun mutablePrimitive(mode: MemoryOrdering) {
        litmusTest(assertSame(setOf(-1, 0, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: MutablePrimitive? = null
            var result = -1
            val writer = thread { published = MutablePrimitive(42) }
            val reader = thread {
                result = published?.read(mode) ?: -1
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun finalReferenceGet() = finalReference(MemoryOrdering.PLAIN, leaksThis = false)

    @Test
    fun finalReferenceGetOpaque() = finalReference(MemoryOrdering.OPAQUE, leaksThis = false)

    @Test
    fun finalReferenceGetAcquire() = finalReference(MemoryOrdering.ACQUIRE, leaksThis = false)

    @Test
    fun finalReferenceGetVolatile() = finalReference(MemoryOrdering.VOLATILE, leaksThis = false)

    @Test
    fun finalReferenceGetWithLeak() = finalReference(MemoryOrdering.PLAIN, leaksThis = true)

    @Test
    fun finalReferenceGetOpaqueWithLeak() = finalReference(MemoryOrdering.OPAQUE, leaksThis = true)

    @Test
    fun finalReferenceGetAcquireWithLeak() = finalReference(MemoryOrdering.ACQUIRE, leaksThis = true)

    @Test
    fun finalReferenceGetVolatileWithLeak() = finalReference(MemoryOrdering.VOLATILE, leaksThis = true)

    private fun finalReference(mode: MemoryOrdering, leaksThis: Boolean) {
        val outcomes = if (leaksThis) setOf(-1, 0, 1) else setOf(-1, 1)
        litmusTest(assertSame(outcomes, UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: FinalReference? = null
            var result = -1
            val writer = thread {
                published = FinalReference(Any()) { if (leaksThis) published = it }
            }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else if (observed.read(mode) == null) 0 else 1
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun finalReferenceGetProtectsMutableField() = finalReferenceProtectsMutableField(MemoryOrdering.PLAIN)

    @Test
    fun finalReferenceGetOpaqueProtectsMutableField() = finalReferenceProtectsMutableField(MemoryOrdering.OPAQUE)

    @Test
    fun finalReferenceGetAcquireProtectsMutableField() = finalReferenceProtectsMutableField(MemoryOrdering.ACQUIRE)

    @Test
    fun finalReferenceGetVolatileProtectsMutableField() = finalReferenceProtectsMutableField(MemoryOrdering.VOLATILE)

    private fun finalReferenceProtectsMutableField(mode: MemoryOrdering) {
        litmusTest(assertSame(setOf(-1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: FinalCellReference? = null
            var result = -1
            val writer = thread { published = FinalCellReference(Cell(42)) }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else observed.read(mode)?.value ?: -2
            }
            writer.join()
            reader.join()
            result
        }
    }

    private class FinalPrimitive(value: Int, publish: (FinalPrimitive) -> Unit) {
        companion object {
            private val handle = MethodHandles.lookup()
                .findVarHandle(FinalPrimitive::class.java, "field", Int::class.javaPrimitiveType)
        }

        @JvmField
        val field: Int

        init {
            publish(this)
            field = value
        }

        fun read(mode: MemoryOrdering): Int = when (mode) {
            MemoryOrdering.PLAIN -> handle.get(this) as Int
            MemoryOrdering.OPAQUE -> handle.getOpaque(this) as Int
            MemoryOrdering.ACQUIRE -> handle.getAcquire(this) as Int
            MemoryOrdering.VOLATILE -> handle.getVolatile(this) as Int
            else -> error("Unsupported read mode: $mode")
        }
    }

    private class MutablePrimitive(value: Int) {
        companion object {
            private val handle = MethodHandles.lookup()
                .findVarHandle(MutablePrimitive::class.java, "field", Int::class.javaPrimitiveType)
        }

        @JvmField
        var field: Int = value

        fun read(mode: MemoryOrdering): Int = when (mode) {
            MemoryOrdering.PLAIN -> handle.get(this) as Int
            MemoryOrdering.OPAQUE -> handle.getOpaque(this) as Int
            MemoryOrdering.ACQUIRE -> handle.getAcquire(this) as Int
            MemoryOrdering.VOLATILE -> handle.getVolatile(this) as Int
            else -> error("Unsupported read mode: $mode")
        }
    }

    private class FinalReference(value: Any, publish: (FinalReference) -> Unit) {
        companion object {
            private val handle = MethodHandles.lookup()
                .findVarHandle(FinalReference::class.java, "field", Any::class.java)
        }

        @JvmField
        val field: Any?

        init {
            publish(this)
            field = value
        }

        fun read(mode: MemoryOrdering): Any? = when (mode) {
            MemoryOrdering.PLAIN -> handle.get(this)
            MemoryOrdering.OPAQUE -> handle.getOpaque(this)
            MemoryOrdering.ACQUIRE -> handle.getAcquire(this)
            MemoryOrdering.VOLATILE -> handle.getVolatile(this)
            else -> error("Unsupported read mode: $mode")
        }
    }

    private class Cell(@JvmField var value: Int)

    private class FinalCellReference(@JvmField val cell: Cell?) {
        companion object {
            private val handle = MethodHandles.lookup()
                .findVarHandle(FinalCellReference::class.java, "cell", Cell::class.java)
        }

        fun read(mode: MemoryOrdering): Cell? = when (mode) {
            MemoryOrdering.PLAIN -> handle.get(this) as Cell?
            MemoryOrdering.OPAQUE -> handle.getOpaque(this) as Cell?
            MemoryOrdering.ACQUIRE -> handle.getAcquire(this) as Cell?
            MemoryOrdering.VOLATILE -> handle.getVolatile(this) as Cell?
            else -> error("Unsupported read mode: $mode")
        }
    }
}
