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

class ThreadForkJoinTest {

    fun operation(): Int {
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

    @Test
    fun test() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::operation,
        outcomes = setOf(0, 1, 2, 3),
    )

}

