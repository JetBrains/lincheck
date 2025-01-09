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
import kotlin.concurrent.*
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
        return synchronized(monitor) { counter }
            .also { listOf(t1, t2, t3).forEach { it.join() } }
    }

    @Test(timeout = TIMEOUT)
    fun testMutex() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::mutex,
        expectedOutcomes = setOf(0, 1, 2, 3)
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
        t1.join()
        t2.join()
        return counter
    }

    @Test(timeout = TIMEOUT)
    fun testDeadlock() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::deadlock,
        expectedFailure = ManagedDeadlockFailure::class,
    )

}