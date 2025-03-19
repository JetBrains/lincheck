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
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

@OptIn(ExperimentalModelCheckingAPI::class)
class PrintingTest {

    /**
     * This aims to test the effects of printing to `stdout` and `stderr`.
     * The test used to hang because of prints, see https://github.com/JetBrains/lincheck/issues/409.
     *
     * See [CyclicBarrierTest] for identical test but with no printing.
     */
    @Test(timeout = TIMEOUT)
    fun testPrints() {
        runConcurrentTest(1) {
            val barrier = CyclicBarrier(2) {
                System.err.println("All threads have reached the barrier. Proceeding...")
            }

            // using different printing methods and standard streams (out/err)
            val t1 = thread {
                print("Thread 1 is waiting at the barrier")
                println()
                barrier.await()
                System.err.println("Thread 1 has passed the barrier")
            }
            val t2 = thread {
                print("Thread 2 is waiting at the barrier")
                println()
                barrier.await()
                System.err.println("Thread 2 has passed the barrier")
            }

            t1.join()
            t2.join()
        }
    }
}