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
import java.lang.invoke.VarHandle
import java.util.concurrent.locks.StampedLock
import kotlin.concurrent.thread

/**
 * Litmus tests for the memory fences of `Unsafe`,
 * and for the store-store and load-load fences of `VarHandle`.
 */
class MemoryFencesTest {

    private class Cells {
        @JvmField var x = 0
        @JvmField var y = 0
    }

    private class SequenceLockedCells {
        @Volatile @JvmField var sequence = 0
        @JvmField var x = 0
        @JvmField var y = 0
    }

    /**
     * `StampedLock` orders the plain accesses of an optimistic reader with memory fences:
     * a store-store fence once the write lock is acquired, and a load fence in `validate`
     * (in JDK 17, both are calls of the internal `Unsafe`).
     */
    @Test
    fun testStampedLockOptimisticRead() {
        // (validated, x, y)
        val tornReads = setOf(Triple(true, 1, 0), Triple(true, 0, 1))
        litmusTest(assertNever(tornReads), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val lock = StampedLock()
            val cells = Cells()
            var validated = false
            var x = -1
            var y = -1
            val writer = thread {
                val stamp = lock.writeLock()
                cells.x = 1
                cells.y = 1
                lock.unlockWrite(stamp)
            }
            val reader = thread {
                val stamp = lock.tryOptimisticRead()
                x = cells.x
                y = cells.y
                validated = lock.validate(stamp)
            }
            writer.join()
            reader.join()
            Triple(validated, x, y)
        }
    }

    @Test
    fun testSequenceLockStoreStoreAndLoadLoadFences() {
        // (validated, x, y)
        val tornReads = setOf(Triple(true, 1, 0), Triple(true, 0, 1))
        litmusTest(assertNever(tornReads), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val cells = SequenceLockedCells()
            var validated = false
            var x = -1
            var y = -1
            val writer = thread {
                cells.sequence = 1
                VarHandle.storeStoreFence()
                cells.x = 1
                cells.y = 1
                cells.sequence = 2
            }
            val reader = thread {
                val sequence = cells.sequence
                x = cells.x
                y = cells.y
                VarHandle.loadLoadFence()
                validated = (sequence and 1) == 0 && cells.sequence == sequence
            }
            writer.join()
            reader.join()
            Triple(validated, x, y)
        }
    }

    @Test
    fun testStoreBufferingUnsafeFullFence() {
        val forbiddenOutcomes = setOf(0 to 0)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val cells = Cells()
            var r0 = -1
            var r1 = -1
            val t0 = thread {
                cells.x = 1
                UnsafeHolder.UNSAFE.fullFence()
                r0 = cells.y
            }
            val t1 = thread {
                cells.y = 1
                UnsafeHolder.UNSAFE.fullFence()
                r1 = cells.x
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }

    @Test
    fun testMessagePassingUnsafeStoreAndLoadFences() {
        val forbiddenOutcomes = setOf(1 to 0)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val cells = Cells()
            var r0 = -1
            var r1 = -1
            val t0 = thread {
                cells.x = 1
                UnsafeHolder.UNSAFE.storeFence()
                cells.y = 1
            }
            val t1 = thread {
                r0 = cells.y
                UnsafeHolder.UNSAFE.loadFence()
                r1 = cells.x
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }
}
