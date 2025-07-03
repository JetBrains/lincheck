/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc

import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Test

class ThreadTest {

    fun threadStart(): Int {
        val counter = AtomicInteger(0)
        thread {
            counter.incrementAndGet()
        }
        return counter.get()
    }

    @Test(timeout = TIMEOUT)
    fun testThreadStart() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::threadStart,
        expectedOutcomes = setOf(0, 1),
    )

    fun threadJoin(): Int {
        val counter = AtomicInteger(0)
        val t1 = thread {
            counter.incrementAndGet()
        }
        val t2 = thread {
            counter.incrementAndGet()
        }
        val t3 = thread {
            counter.incrementAndGet()
        }
        return counter.get().also {
            t1.join()
            t2.join()
            t3.join()
        }
    }

    @Test(timeout = TIMEOUT)
    fun testThreadJoin() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::threadJoin,
        expectedOutcomes = setOf(0, 1, 2, 3),
    )

    fun threadTimedJoin(): Int {
        val counter = AtomicInteger(0)
        val t1 = thread {
            counter.incrementAndGet()
        }
        val t2 = thread {
            counter.incrementAndGet()
        }
        return counter.get().also {
            t1.join(10L)
            t2.join(10L, 1_000)
        }
    }

    @Test(timeout = TIMEOUT)
    fun testThreadTimedJoin() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::threadTimedJoin,
        expectedOutcomes = setOf(0, 1, 2),
    )

}