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

import org.jetbrains.lincheck.Lincheck.runConcurrentTest
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/**
 * This test aims to test the effects of printing to `stdout` and `stderr`.
 * The test used to hang because of prints, see https://github.com/JetBrains/lincheck/issues/409.
 *
 * See [CyclicBarrierTest] for identical test but with no printing.
 */
class PrintingTest {

    private fun runTest(
        print: (String) -> Unit,
        println: (String) -> Unit,
        printlnNoArgs: () -> Unit
    ) {
        runConcurrentTest(1) {
            val barrier = CyclicBarrier(2) {
                println("All threads have reached the barrier. Proceeding...")
            }

            // using different printing methods and standard streams (out/err)
            val t1 = thread {
                print("Thread 1 is waiting at the barrier")
                printlnNoArgs()
                barrier.await()
                println("Thread 1 has passed the barrier")
            }
            val t2 = thread {
                print("Thread 2 is waiting at the barrier")
                printlnNoArgs()
                barrier.await()
                println("Thread 2 has passed the barrier")
            }

            t1.join()
            t2.join()
        }
    }

    @Test(timeout = TIMEOUT)
    fun testKotlinStdout() = runTest(
        ::print,
        ::println,
        ::println
    )

    @Test(timeout = TIMEOUT)
    fun testJavaStdout() = runTest(
        System.out::print,
        System.out::println,
        System.out::println
    )

    @Test(timeout = TIMEOUT)
    fun testJavaStderr()  = runTest(
        System.err::print,
        System.err::println,
        System.err::println
    )
}