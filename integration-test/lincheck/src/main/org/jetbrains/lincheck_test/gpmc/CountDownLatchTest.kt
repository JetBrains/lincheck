/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.gpmc

import org.jetbrains.lincheck.Lincheck
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.jetbrains.lincheck.Lincheck.runConcurrentTestInternal
import org.jetbrains.lincheck.LincheckSettings

class CountDownLatchTest {

    val block = {
        val nThreads = 2
        val threads = mutableListOf<Thread>()
        val latch = CountDownLatch(1)
        val counter = AtomicInteger(0)

        for (i in 0 until nThreads)
        threads += thread {
            latch.await()
            counter.incrementAndGet()
        }

        latch.countDown()
        threads.forEach { it.join() }
        check(counter.get() == nThreads)
    }

    @Test
    fun testLatchCountdown() = Lincheck.runConcurrentTest(10000, block)

    @Ignore("Times out") // TODO: fix loop detector
    @Test
    fun testBarrierEventStructure() = runConcurrentTestInternal(
        10000,
        LincheckSettings(true, 10, 50, 20, false),
        block
    )
}