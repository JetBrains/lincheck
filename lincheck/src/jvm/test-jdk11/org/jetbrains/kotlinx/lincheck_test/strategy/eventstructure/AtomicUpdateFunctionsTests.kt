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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicLongFieldUpdater
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import kotlin.concurrent.thread

/**
 * Tests of the function-update methods of JDK atomics:
 * `getAndUpdate`, `updateAndGet`, `getAndAccumulate` and `accumulateAndGet`.
 *
 * The tests need JDK 9+: on JDK 8, the atomic arrays implement these methods
 * through private helpers that the checker does not model.
 */
class AtomicUpdateFunctionsTests {

    // In the sequential tests, the atomic is read before it is updated:
    // the checker takes the initial value of a location at its first access,
    // and only after that does an update it has not seen show up as a stale read.

    @Test
    fun testAtomicInteger() = checkOutcomes(setOf(listOf(1, 1, 6, 6, 5, 5))) {
        val x = AtomicInteger(1)
        listOf(
            x.get(),
            x.getAndUpdate { it + 1 },
            x.updateAndGet { it * 3 },
            x.getAndAccumulate(4) { value, argument -> value + argument },
            x.accumulateAndGet(5) { value, argument -> value - argument },
            x.get(),
        )
    }

    @Test
    fun testAtomicLong() = checkOutcomes(setOf(listOf(1L, 1L, 6L, 6L, 5L, 5L))) {
        val x = AtomicLong(1L)
        listOf(
            x.get(),
            x.getAndUpdate { it + 1L },
            x.updateAndGet { it * 3L },
            x.getAndAccumulate(4L) { value, argument -> value + argument },
            x.accumulateAndGet(5L) { value, argument -> value - argument },
            x.get(),
        )
    }

    @Test
    fun testAtomicReference() = checkOutcomes(setOf(listOf("a", "a", "abc", "abc", "abcde", "abcde"))) {
        val x = AtomicReference("a")
        listOf(
            x.get(),
            x.getAndUpdate { it + "b" },
            x.updateAndGet { it + "c" },
            x.getAndAccumulate("d") { value, argument -> value + argument },
            x.accumulateAndGet("e") { value, argument -> value + argument },
            x.get(),
        )
    }

    @Test
    fun testAtomicIntegerArray() = checkOutcomes(setOf(listOf(1, 1, 6, 6, 5, 5))) {
        val x = AtomicIntegerArray(intArrayOf(0, 1))
        listOf(
            x.get(1),
            x.getAndUpdate(1) { it + 1 },
            x.updateAndGet(1) { it * 3 },
            x.getAndAccumulate(1, 4) { value, argument -> value + argument },
            x.accumulateAndGet(1, 5) { value, argument -> value - argument },
            x.get(1),
        )
    }

    @Test
    fun testAtomicLongArray() = checkOutcomes(setOf(listOf(1L, 1L, 6L, 6L, 5L, 5L))) {
        val x = AtomicLongArray(longArrayOf(0L, 1L))
        listOf(
            x.get(1),
            x.getAndUpdate(1) { it + 1L },
            x.updateAndGet(1) { it * 3L },
            x.getAndAccumulate(1, 4L) { value, argument -> value + argument },
            x.accumulateAndGet(1, 5L) { value, argument -> value - argument },
            x.get(1),
        )
    }

    @Test
    fun testAtomicReferenceArray() = checkOutcomes(setOf(listOf("a", "a", "abc", "abc", "abcde", "abcde"))) {
        val x = AtomicReferenceArray(arrayOf("", "a"))
        listOf(
            x.get(1),
            x.getAndUpdate(1) { it + "b" },
            x.updateAndGet(1) { it + "c" },
            x.getAndAccumulate(1, "d") { value, argument -> value + argument },
            x.accumulateAndGet(1, "e") { value, argument -> value + argument },
            x.get(1),
        )
    }

    @Test
    fun testAtomicIntegerFieldUpdater() = checkOutcomes(setOf(listOf(1, 1, 6, 6, 5, 5))) {
        val x = Fields()
        listOf(
            Fields.INT.get(x),
            Fields.INT.getAndUpdate(x) { it + 1 },
            Fields.INT.updateAndGet(x) { it * 3 },
            Fields.INT.getAndAccumulate(x, 4) { value, argument -> value + argument },
            Fields.INT.accumulateAndGet(x, 5) { value, argument -> value - argument },
            Fields.INT.get(x),
        )
    }

    @Test
    fun testAtomicLongFieldUpdater() = checkOutcomes(setOf(listOf(1L, 1L, 6L, 6L, 5L, 5L))) {
        val x = Fields()
        listOf(
            Fields.LONG.get(x),
            Fields.LONG.getAndUpdate(x) { it + 1L },
            Fields.LONG.updateAndGet(x) { it * 3L },
            Fields.LONG.getAndAccumulate(x, 4L) { value, argument -> value + argument },
            Fields.LONG.accumulateAndGet(x, 5L) { value, argument -> value - argument },
            Fields.LONG.get(x),
        )
    }

    @Test
    fun testAtomicReferenceFieldUpdater() = checkOutcomes(setOf(listOf("a", "a", "abc", "abc", "abcde", "abcde"))) {
        val x = Fields()
        listOf(
            Fields.REFERENCE.get(x),
            Fields.REFERENCE.getAndUpdate(x) { it + "b" },
            Fields.REFERENCE.updateAndGet(x) { it + "c" },
            Fields.REFERENCE.getAndAccumulate(x, "d") { value, argument -> value + argument },
            Fields.REFERENCE.accumulateAndGet(x, "e") { value, argument -> value + argument },
            Fields.REFERENCE.get(x),
        )
    }

    // In the concurrent tests, two threads increment the same atomic.
    // An outcome lists the final value, the results of the two updates,
    // and the number of times each thread has applied its update function:
    // a thread that loses the race between its read and its CAS applies the function again.

    @Test
    fun testConcurrentUpdateAndGet() = checkOutcomes(incrementOutcomes(resultsAreUpdatedValues = true)) {
        val x = AtomicInteger(0)
        var first = -1
        var second = -1
        var firstApplications = 0
        var secondApplications = 0
        val firstThread = thread {
            first = x.updateAndGet { firstApplications++; it + 1 }
        }
        val secondThread = thread {
            second = x.updateAndGet { secondApplications++; it + 1 }
        }
        firstThread.join()
        secondThread.join()
        listOf(x.get(), first, second, firstApplications, secondApplications)
    }

    @Test
    fun testConcurrentArrayGetAndUpdate() = checkOutcomes(incrementOutcomes(resultsAreUpdatedValues = false)) {
        val x = AtomicIntegerArray(2)
        var first = -1
        var second = -1
        var firstApplications = 0
        var secondApplications = 0
        val firstThread = thread {
            first = x.getAndUpdate(1) { firstApplications++; it + 1 }
        }
        val secondThread = thread {
            second = x.getAndUpdate(1) { secondApplications++; it + 1 }
        }
        firstThread.join()
        secondThread.join()
        listOf(x.get(1), first, second, firstApplications, secondApplications)
    }

    @Test
    fun testConcurrentFieldUpdaterAccumulateAndGet() = checkOutcomes(incrementOutcomes(resultsAreUpdatedValues = true)) {
        val x = Fields()
        var first = -1
        var second = -1
        var firstApplications = 0
        var secondApplications = 0
        val firstThread = thread {
            first = Fields.COUNTER.accumulateAndGet(x, 1) { value, argument -> firstApplications++; value + argument }
        }
        val secondThread = thread {
            second = Fields.COUNTER.accumulateAndGet(x, 1) { value, argument -> secondApplications++; value + argument }
        }
        firstThread.join()
        secondThread.join()
        listOf(Fields.COUNTER.get(x), first, second, firstApplications, secondApplications)
    }

    private fun incrementOutcomes(resultsAreUpdatedValues: Boolean): Set<List<Int>> {
        val (earlier, later) = if (resultsAreUpdatedValues) 1 to 2 else 0 to 1
        return setOf(
            // one update completes before the other one starts
            listOf(2, earlier, later, 1, 1),
            listOf(2, later, earlier, 1, 1),
            // both updates read 0, and the one that loses the CAS is retried
            listOf(2, earlier, later, 1, 2),
            listOf(2, later, earlier, 2, 1),
        )
    }

    private inline fun <reified Outcome> checkOutcomes(outcomes: Set<Outcome>, noinline block: () -> Outcome) {
        for (memoryModel in MemoryModel.entries) {
            litmusTest(assertSame(outcomes), memoryModel, requireExhaustion = true, block = block)
        }
    }

    private class Fields {
        @Volatile @JvmField var int = 1
        @Volatile @JvmField var long = 1L
        @Volatile @JvmField var reference = "a"
        @Volatile @JvmField var counter = 0

        companion object {
            val INT: AtomicIntegerFieldUpdater<Fields> =
                AtomicIntegerFieldUpdater.newUpdater(Fields::class.java, "int")
            val LONG: AtomicLongFieldUpdater<Fields> =
                AtomicLongFieldUpdater.newUpdater(Fields::class.java, "long")
            val REFERENCE: AtomicReferenceFieldUpdater<Fields, String> =
                AtomicReferenceFieldUpdater.newUpdater(Fields::class.java, String::class.java, "reference")
            val COUNTER: AtomicIntegerFieldUpdater<Fields> =
                AtomicIntegerFieldUpdater.newUpdater(Fields::class.java, "counter")
        }
    }
}
