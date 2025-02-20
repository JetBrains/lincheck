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
import java.util.concurrent.CountDownLatch

@OptIn(ExperimentalModelCheckingAPI::class)
class PrintingTest {
    @Ignore("Println's hang the execution")
    @Test(timeout = TIMEOUT)
    fun testPrintsWithLatch() {
        runConcurrentTest(1) {
            println("Before latch creation")
            val latch = CountDownLatch(1) // class from instrumented module
            println("After latch creation")
            latch.countDown()
            println("Latch count down")
        }
    }
}