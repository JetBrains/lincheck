/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmus

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.UNKNOWN
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertNever
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertSame
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertSometimes
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmusTest
import org.junit.Test
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PlainAccessMemoryModelTest {
    @Test
    fun TestPlainReadsSeparatedByUnrelatedOpaqueRead() {
        val outcomes = setOf(Triple(0, 0, 0), Triple(0, 0, 1), Triple(1, 0, 0), Triple(1, 0, 1))
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val unrelated = AtomicInteger(0)
            var first = -1
            var middle = -1
            var second = -1
            val writer = thread { x.setPlain(1) }
            val reader = thread {
                first = x.getPlain()
                middle = unrelated.getOpaque()
                second = x.getPlain()
            }
            writer.join()
            reader.join()
            Triple(first, middle, second)
        }
    }

    @Test
    fun TestPlainReadsSeparatedByAcquireFence() {
        litmusTest(assertSame(setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = -1
            var second = -1
            val writer = thread { x.setPlain(1) }
            val reader = thread {
                first = x.getPlain()
                VarHandle.acquireFence()
                second = x.getPlain()
            }
            writer.join()
            reader.join()
            first to second
        }
    }

    @Test
    fun TestPlainReadsSeparatedByLoadLoadFence() {
        litmusTest(assertSame(setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = -1
            var second = -1
            val writer = thread { x.setPlain(1) }
            val reader = thread {
                first = x.getPlain()
                VarHandle.loadLoadFence()
                second = x.getPlain()
            }
            writer.join()
            reader.join()
            first to second
        }
    }

    @Test
    fun TestAliasedPlainOpaquePlainReads() {
        val outcomes = setOf(Triple(0, 0, 0), Triple(0, 0, 1), Triple(0, 1, 1), Triple(1, 1, 1))
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val alias = x
            var first = -1
            var middle = -1
            var last = -1
            val writer = thread { x.setPlain(1) }
            val reader = thread {
                first = x.getPlain()
                middle = alias.getOpaque()
                last = x.getPlain()
            }
            writer.join()
            reader.join()
            Triple(first, middle, last)
        }
    }

    @Test
    fun TestAliasedPlainOpaquePlainReadsAfterCommonSubexpressionElimination() {
        // The modeled either-endpoint coherence rule does not validate this optimization across an opaque read.
        val outcomes = setOf(Triple(0, 0, 0), Triple(0, 1, 0), Triple(1, 1, 1))
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val alias = x
            var first = -1
            var middle = -1
            var last = -1
            val writer = thread { x.setPlain(1) }
            val reader = thread {
                val cached = x.getPlain()
                first = cached
                middle = alias.getOpaque()
                last = cached
            }
            writer.join()
            reader.join()
            Triple(first, middle, last)
        }
    }

    @Test
    fun TestAliasedPlainReads() {
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
    fun TestAliasedPlainReadsAfterCommonSubexpressionElimination() {
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
    fun TestOpaqueReadsOfConcurrentPlainWrites() {
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
    fun TestOppositeOpaqueObservationsOfConcurrentPlainWrites() {
        checkOppositeOpaqueObservations(opaqueSecondWrite = false)
    }

    @Test
    fun TestOppositeOpaqueObservationsOfConcurrentPlainAndOpaqueWrites() {
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
    fun TestPlainReadAfterReleaseAcquirePublication() {
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
    fun TestLatestPlainWriteAfterCrossThreadPublication() {
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
    fun TestOwnPlainWriteRemainsVisibleAfterAnInterveningRead() {
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
    fun TestPlainCompareAndSetRemainsAtomic() {
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
    fun TestPlainReadBeforePlainCompareAndSetWithPlainWriter() {
        checkPlainReadBeforePlainCompareAndSet(opaqueWriter = false)
    }

    @Test
    fun TestPlainReadBeforePlainCompareAndSetWithOpaqueWriter() {
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
    fun TestPlainCompareAndSetCompetingForAPlainWrite() {
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
    fun TestCasAcrossPlainWritesObservedByOpaqueReads() {
        checkCasAcrossWritesObservedByOpaqueReads(opaqueSource = false, opaqueCompetitor = false)
    }

    @Test
    fun TestCasAcrossPlainSourceAndOpaqueWriteObservedByOpaqueReads() {
        checkCasAcrossWritesObservedByOpaqueReads(opaqueSource = false, opaqueCompetitor = true)
    }

    @Test
    fun TestCasAcrossOpaqueSourceAndPlainWriteObservedByOpaqueReads() {
        checkCasAcrossWritesObservedByOpaqueReads(opaqueSource = true, opaqueCompetitor = false)
    }

    @Test
    fun TestCasAcrossOpaqueWritesObservedByOpaqueReads() {
        checkCasAcrossWritesObservedByOpaqueReads(opaqueSource = true, opaqueCompetitor = true)
    }

    private fun checkCasAcrossWritesObservedByOpaqueReads(opaqueSource: Boolean, opaqueCompetitor: Boolean) {
        // Total coherence would force W1 < W2 < W3, interrupting the successful CAS from W1 to W3.
        // If either ordinary write is plain, one of these edges need not exist in partial coherence.
        // Java has no opaque CAS; the checker promotes both halves of a plain RMW to opaque.
        // Recording success distinguishes the RMW witness from a possibly spurious weak-CAS failure.
        val witness = setOf(Triple(2, 3, true))
        val verifier = if (opaqueSource && opaqueCompetitor) assertNever(witness) else assertSometimes(witness)
        litmusTest(verifier, MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = -1
            var second = -1
            var success = false
            val readerWriter = thread {
                if (opaqueSource) x.setOpaque(1) else x.setPlain(1)
                first = x.getOpaque()
                second = x.getOpaque()
            }
            val updater = thread { success = x.weakCompareAndSetPlain(1, 3) }
            val competingWriter = thread {
                if (opaqueCompetitor) x.setOpaque(2) else x.setPlain(2)
            }
            readerWriter.join()
            updater.join()
            competingWriter.join()
            Triple(first, second, success)
        }
    }

    @Test
    fun TestPlainReadBeforeFailedReleaseCompareAndExchange() {
        // An impossible expected value exposes the failed read without weak-CAS spurious failures.
        val outcomes = setOf(0 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = -1
            var second = -1
            val writer = thread { x.setPlain(1) }
            val reader = thread {
                first = x.getPlain()
                second = x.compareAndExchangeRelease(2, 3)
            }
            writer.join()
            reader.join()
            first to second
        }
    }

    @Test
    fun TestFailedReleaseCompareAndExchangeBeforePlainRead() {
        val outcomes = setOf(0 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = -1
            var second = -1
            val writer = thread { x.setPlain(1) }
            val reader = thread {
                first = x.compareAndExchangeRelease(2, 3)
                second = x.getPlain()
            }
            writer.join()
            reader.join()
            first to second
        }
    }

    @Test
    fun TestPlainReadBeforeSuccessfulReleaseCompareAndExchange() {
        val outcomes = setOf(0 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = -1
            var witness = -1
            val writer = thread { x.setPlain(1) }
            val updater = thread {
                first = x.getPlain()
                witness = x.compareAndExchangeRelease(0, 2)
            }
            writer.join()
            updater.join()
            first to witness
        }
    }

    @Test
    fun TestAcquireCompareAndExchangeWriteHasAtomicCoherence() {
        val forbidden = setOf(listOf(0, 1, 2, 2, 1))
        litmusTest(assertNever(forbidden), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var witness = -1
            var first = -1
            var second = -1
            var third = -1
            var fourth = -1
            val updater = thread { witness = x.compareAndExchangeAcquire(0, 1) }
            val writer = thread { x.setOpaque(2) }
            val firstReader = thread {
                first = x.getOpaque()
                second = x.getOpaque()
            }
            val secondReader = thread {
                third = x.getOpaque()
                fourth = x.getOpaque()
            }
            updater.join()
            writer.join()
            firstReader.join()
            secondReader.join()
            listOf(witness, first, second, third, fourth)
        }
    }

    @Test
    fun TestAtomicCoherenceOrderExtendsThroughHappensBeforeToPlainWrites() {
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
    fun TestConsecutivePlainCompareAndSetsCannotConsumeTheSamePlainWrite() {
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
    fun TestCoherenceThroughPlainRmwSourceAtomicOrderAndPlainSuffix() {
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
    fun TestOpaqueReadsAcrossPlainClusters() {
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
    fun TestPlainClustersKeepAtomicBoundsLocalToEachField() {
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
    fun TestConcurrentPlainWriteCanOverlapAtomicBounds() {
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
    fun TestIndependentRmwChainsWithPlainSources() {
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
    fun TestRmwChainWithPlainSourceAndConcurrentOpaqueWrite() {
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

    private class PlainPair {
        @JvmField var x = 0
        @JvmField var y = 0
    }

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

    @Test
    fun TestPlainLoadBuffering() {
        litmusTest(assertSame(setOf(0 to 0, 0 to 1, 1 to 0)), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val pair = PlainPair()
            var first = -1
            var second = -1
            val t1 = thread {
                first = pair.x
                pair.y = 1
            }
            val t2 = thread {
                second = pair.y
                pair.x = 1
            }
            t1.join()
            t2.join()
            first to second
        }
    }
}
