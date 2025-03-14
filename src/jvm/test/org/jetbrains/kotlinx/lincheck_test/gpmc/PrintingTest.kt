/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc

import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@OptIn(ExperimentalModelCheckingAPI::class)
class PrintingTest {

    @Ignore("Println's hang the execution")
    @Test(timeout = TIMEOUT)
    fun testPrintsWithLatch() {
        runConcurrentTest(1) {
            val numberOfThreads = 2
            val barrier = CyclicBarrier(numberOfThreads) {
                println("All threads have reached the barrier. Proceeding...")
            }

            val t1 = thread {
                println("Thread 1 is waiting at the barrier")
                barrier.await()
                println("Thread 1 has passed the barrier")
            }
            val t2 = thread {
                println("Thread 1 is waiting at the barrier")
                barrier.await()
                println("Thread 1 has passed the barrier")
            }

            t1.join()
            t2.join()
        }
    }
}