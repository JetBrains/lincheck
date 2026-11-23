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
import org.junit.Test
import kotlin.concurrent.thread

class UnsafeTests {

    // In the tests below, a location is read before `putOrdered*` writes it:
    // the checker takes the initial value of a location at its first access,
    // and only after that does a write it has not seen show up as a stale read.

    @Test
    fun testPutOrderedInt() {
        for (memoryModel in MemoryModel.entries) {
            litmusTest(assertSame(setOf(listOf(0, 1, 1, 0, 2, 2))), memoryModel, requireExhaustion = true) {
                val cell = Cell()
                val array = IntArray(2)
                val initialField = cell.x
                val initialElement = array[1]
                U.putOrderedInt(cell, X, 1)
                U.putOrderedInt(array, INT_ARRAY_BASE + INT_ARRAY_SCALE, 2)
                val ownField = cell.x
                val ownElement = array[1]
                var otherField = -1
                var otherElement = -1
                val reader = thread {
                    otherField = U.getIntVolatile(cell, X)
                    otherElement = U.getIntVolatile(array, INT_ARRAY_BASE + INT_ARRAY_SCALE)
                }
                reader.join()
                listOf(initialField, ownField, otherField, initialElement, ownElement, otherElement)
            }
        }
    }

    @Test
    fun testPutOrderedLong() {
        for (memoryModel in MemoryModel.entries) {
            litmusTest(assertSame(setOf(listOf(0L, 1L, 1L, 0L, 2L, 2L))), memoryModel, requireExhaustion = true) {
                val cell = Cell()
                val array = LongArray(2)
                val initialField = cell.long
                val initialElement = array[1]
                U.putOrderedLong(cell, LONG, 1L)
                U.putOrderedLong(array, LONG_ARRAY_BASE + LONG_ARRAY_SCALE, 2L)
                val ownField = cell.long
                val ownElement = array[1]
                var otherField = -1L
                var otherElement = -1L
                val reader = thread {
                    otherField = U.getLongVolatile(cell, LONG)
                    otherElement = U.getLongVolatile(array, LONG_ARRAY_BASE + LONG_ARRAY_SCALE)
                }
                reader.join()
                listOf(initialField, ownField, otherField, initialElement, ownElement, otherElement)
            }
        }
    }

    @Test
    fun testPutOrderedObject() {
        val outcome = listOf<Any?>(null, "a", "a", null, "b", "b")
        for (memoryModel in MemoryModel.entries) {
            litmusTest(assertSame(setOf(outcome)), memoryModel, requireExhaustion = true) {
                val cell = Cell()
                val array = arrayOfNulls<Any>(2)
                val initialField = cell.ref
                val initialElement = array[1]
                U.putOrderedObject(cell, REF, "a")
                U.putOrderedObject(array, REF_ARRAY_BASE + REF_ARRAY_SCALE, "b")
                val ownField = cell.ref
                val ownElement = array[1]
                var otherField: Any? = null
                var otherElement: Any? = null
                val reader = thread {
                    otherField = U.getObjectVolatile(cell, REF)
                    otherElement = U.getObjectVolatile(array, REF_ARRAY_BASE + REF_ARRAY_SCALE)
                }
                reader.join()
                listOf(initialField, ownField, otherField, initialElement, ownElement, otherElement)
            }
        }
    }

    @Test
    fun testPutOrderedPublishesPrecedingPlainWrite() {
        // `putOrdered*` is a release write: a volatile read that observes it
        // also observes the plain write preceding it, so (1, 0) is forbidden.
        val outcomes = setOf(0 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val cell = Cell()
            var flag = -1
            var value = -1
            val writer = thread {
                cell.x = 1
                U.putOrderedInt(cell, Y, 1)
            }
            val reader = thread {
                flag = U.getIntVolatile(cell, Y)
                value = cell.x
            }
            writer.join()
            reader.join()
            flag to value
        }
    }

    @Test
    fun testPutOrderedStoreBuffering() {
        // A release write, unlike a volatile one, can be delayed past the subsequent read of its thread.
        checkStoreBuffering(volatileWrites = false, outcomes = setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1))
        checkStoreBuffering(volatileWrites = true, outcomes = setOf(0 to 1, 1 to 0, 1 to 1))
    }

    private fun checkStoreBuffering(volatileWrites: Boolean, outcomes: Set<Pair<Int, Int>>) {
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val cell = Cell()
            var first = -1
            var second = -1
            val firstThread = thread {
                if (volatileWrites) U.putIntVolatile(cell, X, 1) else U.putOrderedInt(cell, X, 1)
                first = U.getIntVolatile(cell, Y)
            }
            val secondThread = thread {
                if (volatileWrites) U.putIntVolatile(cell, Y, 1) else U.putOrderedInt(cell, Y, 1)
                second = U.getIntVolatile(cell, X)
            }
            firstThread.join()
            secondThread.join()
            first to second
        }
    }

    // `Unsafe.unpark`'s own parameter is statically typed `Object` (unlike, say, `LockSupport.unpark(Thread)`,
    // where the local variable passed through to `Unsafe.unpark` happens to already be declared `Thread`).
    // A call site whose argument keeps that static `Object` type -- e.g. `org.agrona.UnsafeApi.unpark(Object)`,
    // the real crash the hunt found -- used to make the instrumentation push a value the verifier only knew as
    // `Object` into a call expecting `Thread`, which the class verifier rejected with a `VerifyError`,
    // leaving the whole class uninstrumented.
    class UnparkObjectArgHolder {
        @Volatile var value = 0
        fun write() { value = 1 }
        // Never called: merely being present in the class used to break instrumentation of the whole class.
        fun wake(target: Any) = U.unpark(target)
    }

    @Test
    fun testUnparkWithObjectTypedArgument() {
        litmusTest(assertSame(setOf(1)), requireExhaustion = true) {
            val holder = UnparkObjectArgHolder()
            val before = holder.value
            val writer = thread { holder.write() }
            writer.join()
            before + holder.value
        }
    }

    private class Cell {
        @JvmField var x = 0
        @JvmField var y = 0
        @JvmField var long = 0L
        @JvmField var ref: Any? = null
    }

    @Suppress("DEPRECATION")
    private companion object {
        val U = UnsafeHolder.UNSAFE

        val X = U.objectFieldOffset(Cell::class.java.getDeclaredField("x"))
        val Y = U.objectFieldOffset(Cell::class.java.getDeclaredField("y"))
        val LONG = U.objectFieldOffset(Cell::class.java.getDeclaredField("long"))
        val REF = U.objectFieldOffset(Cell::class.java.getDeclaredField("ref"))

        val INT_ARRAY_BASE = U.arrayBaseOffset(IntArray::class.java).toLong()
        val INT_ARRAY_SCALE = U.arrayIndexScale(IntArray::class.java).toLong()
        val LONG_ARRAY_BASE = U.arrayBaseOffset(LongArray::class.java).toLong()
        val LONG_ARRAY_SCALE = U.arrayIndexScale(LongArray::class.java).toLong()
        val REF_ARRAY_BASE = U.arrayBaseOffset(Array<Any?>::class.java).toLong()
        val REF_ARRAY_SCALE = U.arrayIndexScale(Array<Any?>::class.java).toLong()
    }
}
