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

import org.jetbrains.kotlinx.lincheck.strategy.*
import kotlin.concurrent.thread
import org.junit.Test

class MonitorTest {

    fun mutex(): Int {
        var counter = 0
        val monitor = Any()
        val t1 = thread {
            synchronized(monitor) { counter++ }
        }
        val t2 = thread {
            synchronized(monitor) { counter++ }
        }
        val t3 = thread {
            synchronized(monitor) { counter++ }
        }
        return counter.also {
            t1.join()
            t2.join()
            t3.join()
        }
    }

    @Test(timeout = TIMEOUT)
    fun testMutex() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::mutex,
        outcomes = setOf(0, 1, 2, 3)
    )

}

class MonitorDeadlockIsolatedTest {

    fun deadlock(): Int {
        var counter = 0
        val monitor1 = Any()
        val monitor2 = Any()
        val t1 = thread {
            synchronized(monitor1) {
                synchronized(monitor2) {
                    counter++
                }
            }
        }
        val t2 = thread {
            synchronized(monitor2) {
                synchronized(monitor1) {
                    counter++
                }
            }
        }
        return counter.also {
            t1.join()
            t2.join()
        }
    }

    @Test(timeout = TIMEOUT)
    fun testDeadlock() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::deadlock,
        expectedFailure = ManagedDeadlockFailure::class,
    )

}