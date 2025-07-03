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
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import org.junit.Test

class ParkingTest {

    fun parkUnpark(): Int {
        val counter = AtomicInteger(0)
        val mainThread = Thread.currentThread()
        val t1 = thread {
            counter.incrementAndGet()
            LockSupport.unpark(mainThread)
        }
        LockSupport.park()
        return counter.get().also {
            t1.join()
        }
    }

    @Test(timeout = TIMEOUT)
    fun parkUnparkTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::parkUnpark,
        // can be both because of the spurious wake-ups
        expectedOutcomes = setOf(0, 1)
    )

    fun preemptiveUnpark(): Int {
        val mainThread = Thread.currentThread()
        LockSupport.unpark(mainThread)
        LockSupport.park()
        return 0
    }

    @Test(timeout = TIMEOUT)
    fun preemptiveUnparkTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::preemptiveUnpark,
        expectedOutcomes = setOf(0)
    )

}