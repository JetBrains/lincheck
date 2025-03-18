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
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

@OptIn(ExperimentalModelCheckingAPI::class)
class PrintingTest {

    /**
     * This test would pass if `println`'s were not used.
     * See [CyclicBarrierTest] for identical test but with no printing.
     */
    @Ignore("Println's hang the execution")
    @Test(timeout = TIMEOUT)
    fun testPrints() {
        runConcurrentTest(1) {
            val barrier = CyclicBarrier(2) {
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