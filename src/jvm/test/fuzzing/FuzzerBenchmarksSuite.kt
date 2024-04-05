/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package fuzzing

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.junit.runners.Suite.SuiteClasses

@RunWith(Suite::class)
@SuiteClasses(
    ConcurrentLinkedDequeTest::class,
    LockFreeSetTest::class,
    NonBlockingHashMapLongTest::class,
    SnapTreeTest::class,
    // MutexTest::class, // CPU throttle
    ConcurrentRadixTreeTest::class,
    ConcurrentSuffixTreeTest::class,
    LogicalOrderingAVLTest::class,
    ConcurrencyOptimalMapTest::class,
    AbstractQueueSynchronizerTest::class, // does not work (NoSuchMethodException: fuzzing.SemaphoreSequential.acquire() during minimization)
    CATreeTest::class
)
class FuzzerBenchmarksSuite {
    @Before
    fun setUp() {
        System.gc()
    }

    companion object {
        @AfterClass
        @JvmStatic
        fun tearDown() {
            // Do some when all tests finished
            println("All tests finished")
        }
    }
}