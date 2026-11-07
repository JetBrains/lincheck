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
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PlainAccessMemoryModelTest {
    @Test
    fun testAliasedPlainReads() {
        val outcomes = buildSet {
            for (first in 0..2) {
                for (second in 0..2) {
                    for (third in 0..2) add(Triple(first, second, third))
                }
            }
        }
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = Cell()
            val y = x
            var first = -1
            var second = -1
            var third = -1
            val writer = thread {
                x.value = 1
                x.value = 2
            }
            val reader = thread {
                first = x.value
                second = y.value
                third = x.value
            }
            writer.join()
            reader.join()
            Triple(first, second, third)
        }
    }

    @Test
    fun testAliasedPlainReadsAfterCommonSubexpressionElimination() {
        val outcomes = buildSet {
            for (first in 0..2) {
                for (second in 0..2) add(Triple(first, second, first))
            }
        }
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = Cell()
            val y = x
            var first = -1
            var second = -1
            var third = -1
            val writer = thread {
                x.value = 1
                x.value = 2
            }
            val reader = thread {
                val cached = x.value
                first = cached
                second = y.value
                third = cached
            }
            writer.join()
            reader.join()
            Triple(first, second, third)
        }
    }

    @Test
    fun testOpaqueReadsOfConcurrentPlainWrites() {
        val outcomes = buildSet {
            for (first in 0..2) {
                for (second in 0..2) {
                    for (third in 0..2) {
                        if ((first == 0 || second != 0) && (second == 0 || third != 0)) {
                            add(Triple(first, second, third))
                        }
                    }
                }
            }
        }
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = -1
            var second = -1
            var third = -1
            val firstWriter = thread { x.setPlain(1) }
            val secondWriter = thread { x.setPlain(2) }
            val reader = thread {
                first = x.getOpaque()
                second = x.getOpaque()
                third = x.getOpaque()
            }
            firstWriter.join()
            secondWriter.join()
            reader.join()
            Triple(first, second, third)
        }
    }

    @Test
    fun testOppositeOpaqueObservationsOfConcurrentPlainWrites() {
        checkOppositeOpaqueObservations(opaqueSecondWrite = false)
    }

    @Test
    fun testOppositeOpaqueObservationsOfConcurrentPlainAndOpaqueWrites() {
        checkOppositeOpaqueObservations(opaqueSecondWrite = true)
    }

    private fun checkOppositeOpaqueObservations(opaqueSecondWrite: Boolean) {
        val pairs = setOf(0 to 0, 0 to 1, 0 to 2, 1 to 1, 1 to 2, 2 to 1, 2 to 2)
        val outcomes = buildSet {
            for ((first, second) in pairs) {
                for ((third, fourth) in pairs) add(listOf(first, second, third, fourth))
            }
        }
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = -1
            var second = -1
            var third = -1
            var fourth = -1
            val firstWriter = thread { x.setPlain(1) }
            val secondWriter = thread {
                if (opaqueSecondWrite) x.setOpaque(2) else x.setPlain(2)
            }
            val firstReader = thread {
                first = x.getOpaque()
                second = x.getOpaque()
            }
            val secondReader = thread {
                third = x.getOpaque()
                fourth = x.getOpaque()
            }
            firstWriter.join()
            secondWriter.join()
            firstReader.join()
            secondReader.join()
            listOf(first, second, third, fourth)
        }
    }

    @Test
    fun testPlainReadAfterReleaseAcquirePublication() {
        val outcomes = setOf(0 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = Cell()
            val published = AtomicInteger(0)
            var publication = -1
            var value = -1
            val writer = thread {
                x.value = 1
                published.setRelease(1)
            }
            val reader = thread {
                publication = published.getAcquire()
                value = x.value
            }
            writer.join()
            reader.join()
            publication to value
        }
    }

    @Test
    fun testLatestPlainWriteAfterCrossThreadPublication() {
        val outcomes = setOf(0 to 1, 1 to 2)
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val published = AtomicInteger(0)
            var publication = -1
            // The happens-before-latest write belongs to the first-created thread.
            val laterWriter = thread {
                publication = published.getAcquire()
                if (publication == 1) x.setPlain(2)
            }
            val earlierWriter = thread {
                x.setPlain(1)
                published.setRelease(1)
            }
            laterWriter.join()
            earlierWriter.join()
            publication to x.getOpaque()
        }
    }

    @Test
    fun testOwnPlainWriteRemainsVisibleAfterAnInterveningRead() {
        val outcomes = setOf(1 to 1, 1 to 2, 2 to 1, 2 to 2)
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = -1
            var second = -1
            val readerWriter = thread {
                x.setPlain(1)
                first = x.getPlain()
                second = x.getPlain()
            }
            val writer = thread { x.setPlain(2) }
            readerWriter.join()
            writer.join()
            first to second
        }
    }

    @Test
    fun testPlainCompareAndSetRemainsAtomic() {
        val outcomes = setOf(true to false, false to true)
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = false
            var second = false
            val firstThread = thread { first = x.weakCompareAndSetPlain(0, 1) }
            val secondThread = thread { second = x.weakCompareAndSetPlain(0, 2) }
            firstThread.join()
            secondThread.join()
            first to second
        }
    }

    @Test
    fun testPlainReadBeforePlainCompareAndSetWithPlainWriter() {
        checkPlainReadBeforePlainCompareAndSet(opaqueWriter = false)
    }

    @Test
    fun testPlainReadBeforePlainCompareAndSetWithOpaqueWriter() {
        checkPlainReadBeforePlainCompareAndSet(opaqueWriter = true)
    }

    private fun checkPlainReadBeforePlainCompareAndSet(opaqueWriter: Boolean) {
        // Both RMW parts have at least opaque semantics in this model.
        // Thus (1, true) violates read-read coherence even with a competing plain write.
        val outcomes = setOf(0 to false, 0 to true, 1 to false)
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var value = -1
            var success = false
            val writer = thread {
                if (opaqueWriter) x.setOpaque(1) else x.setPlain(1)
            }
            val readerUpdater = thread {
                value = x.getPlain()
                success = x.weakCompareAndSetPlain(0, 2)
            }
            writer.join()
            readerUpdater.join()
            value to success
        }
    }

    @Test
    fun testPlainCompareAndSetCompetingForAPlainWrite() {
        val outcomes = setOf(false to false, true to false, false to true)
        litmusTest(assertSame(outcomes, executionCount = UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = false
            var second = false
            val writer = thread { x.setPlain(1) }
            val firstThread = thread { first = x.weakCompareAndSetPlain(1, 2) }
            val secondThread = thread { second = x.weakCompareAndSetPlain(1, 3) }
            writer.join()
            firstThread.join()
            secondThread.join()
            first to second
        }
    }

    @Test
    fun testAtomicCoherenceOrderExtendsThroughHappensBeforeToPlainWrites() {
        val outcomes = setOf(0 to 0, 0 to 1, 2 to 1, 3 to 1)
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var first = -1
            var second = -1
            val readerWriter = thread {
                first = x.getPlain()
                x.set(1)
                second = y.get()
            }
            val writer = thread {
                y.set(1)
                x.set(2)
                x.setPlain(3)
            }
            readerWriter.join()
            writer.join()
            first to second
        }
    }

    @Test
    fun testConsecutivePlainCompareAndSetsCannotConsumeTheSamePlainWrite() {
        val outcomes = setOf(false to false, true to false, false to true)
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = false
            var second = false
            val writer = thread { x.setPlain(1) }
            val updater = thread {
                first = x.weakCompareAndSetPlain(1, 2)
                second = x.weakCompareAndSetPlain(1, 3)
            }
            writer.join()
            updater.join()
            first to second
        }
    }

    @Test
    fun testCoherenceThroughPlainRmwSourceAtomicOrderAndPlainSuffix() {
        val outcomes = buildSet {
            for (success in listOf(false, true)) {
                val maximum = if (success) 5 else 2
                for (first in 0..maximum) {
                    for (second in first..maximum) add(Triple(success, first, second))
                }
            }
        }
        // Failed CAS reads of 0 and 1 produce the same Boolean result.
        litmusTest(assertSame(outcomes, executionCount = UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var success = false
            var first = -1
            var second = -1
            val writer = thread {
                x.setPlain(1)
                x.setPlain(2)
            }
            val updater = thread {
                success = x.weakCompareAndSetPlain(2, 3)
                if (success) {
                    x.setOpaque(4)
                    x.setPlain(5)
                }
            }
            val reader = thread {
                first = x.getOpaque()
                second = x.getOpaque()
            }
            writer.join()
            updater.join()
            reader.join()
            Triple(success, first, second)
        }
    }

    @Test
    fun testOpaqueReadsAcrossPlainClusters() {
        val outcomes = buildSet {
            for (first in 0..5) {
                for (second in first..5) add(first to second)
            }
        }
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = -1
            var second = -1
            val writer = thread {
                x.setPlain(1)
                x.setOpaque(2)
                x.setPlain(3)
                x.setOpaque(4)
                x.setPlain(5)
            }
            val reader = thread {
                first = x.getOpaque()
                second = x.getOpaque()
            }
            writer.join()
            reader.join()
            first to second
        }
    }

    @Test
    fun testPlainClustersKeepAtomicBoundsLocalToEachField() {
        val outcomes = setOf(0 to 0, 0 to 1, 0 to 2, 1 to 1, 1 to 2, 2 to 2)
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val cells = TwoFieldCell()
            var first = -1
            var second = -1
            val writer = thread {
                // Both plain writes initially have the same allocation event as their only atomic bound.
                cells.first = 1
                cells.second = 1
                TwoFieldCell.FIRST.setOpaque(cells, 2)
                TwoFieldCell.SECOND.setOpaque(cells, 2)
            }
            val reader = thread {
                first = TwoFieldCell.SECOND.getOpaque(cells) as Int
                second = TwoFieldCell.SECOND.getOpaque(cells) as Int
            }
            writer.join()
            reader.join()
            first to second
        }
    }

    @Test
    fun testConcurrentPlainWriteCanOverlapAtomicBounds() {
        val outcomes = buildSet {
            for (first in 0..4) {
                for (second in 0..4) {
                    if (first == 0 || second != 0 && (first == 4 || second == 4 || first <= second)) {
                        add(first to second)
                    }
                }
            }
        }
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = -1
            var second = -1
            val writer = thread {
                x.setPlain(1)
                x.setOpaque(2)
                x.setPlain(3)
            }
            val concurrentWriter = thread { x.setPlain(4) }
            val reader = thread {
                first = x.getOpaque()
                second = x.getOpaque()
            }
            writer.join()
            concurrentWriter.join()
            reader.join()
            first to second
        }
    }

    @Test
    fun testIndependentRmwChainsWithPlainSources() {
        val outcomes = setOf(false to false, false to true, true to false, true to true)
        litmusTest(assertSame(outcomes, executionCount = UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = false
            var second = false
            val firstUpdater = thread {
                x.setPlain(1)
                first = x.weakCompareAndSetPlain(1, 2)
            }
            val secondUpdater = thread {
                x.setPlain(3)
                second = x.weakCompareAndSetPlain(3, 4)
            }
            firstUpdater.join()
            secondUpdater.join()
            first to second
        }
    }

    @Test
    fun testRmwChainWithPlainSourceAndConcurrentOpaqueWrite() {
        val outcomes = setOf(false to false, true to false, true to true)
        litmusTest(assertSame(outcomes, executionCount = UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = false
            var second = false
            val writer = thread { x.setPlain(1) }
            val updater = thread {
                first = x.weakCompareAndSetPlain(1, 2)
                second = x.weakCompareAndSetPlain(2, 3)
            }
            val concurrentWriter = thread { x.setOpaque(4) }
            writer.join()
            updater.join()
            concurrentWriter.join()
            first to second
        }
    }

    private class Cell(var value: Int = 0)

    private class TwoFieldCell {
        @JvmField
        var first: Int = 0

        @JvmField
        var second: Int = 0

        companion object {
            val FIRST: VarHandle = MethodHandles.lookup()
                .findVarHandle(TwoFieldCell::class.java, "first", Int::class.javaPrimitiveType)
            val SECOND: VarHandle = MethodHandles.lookup()
                .findVarHandle(TwoFieldCell::class.java, "second", Int::class.javaPrimitiveType)
        }
    }
}
