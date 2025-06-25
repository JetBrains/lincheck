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

class CyclicBarrierTest {

    @Test
    fun testBarrier() = runConcurrentTest(10000) {
        val barrier = CyclicBarrier(2)

        val t1 = thread {
            barrier.await()
        }
        val t2 = thread {
            barrier.await()
        }

        t1.join()
        t2.join()
    }
}