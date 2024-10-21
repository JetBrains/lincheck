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

import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class SpinLoopTest {

    fun livelock(): Int {
        var counter = 0
        val lock1 = SpinLock()
        val lock2 = SpinLock()
        val t1 = thread {
            lock1.withLock { 
                lock2.withLock { 
                    counter++
                }
            }
        }
        val t2 = thread {
            lock2.withLock { 
                lock1.withLock { 
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
    fun testLivelock() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::livelock,
        outcomes = setOf(0, 1, 2)
    )

}

private class SpinLock {
    private val lock = AtomicBoolean(false)

    fun lock() {
        while (!lock.compareAndSet(false, true)) {
            Thread.yield()
        }
    }

    fun unlock() {
        lock.set(false)
    }
}

private fun <R> SpinLock.withLock(block: () -> R): R {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}