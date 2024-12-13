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

import org.jetbrains.kotlinx.lincheck.strategy.ManagedDeadlockFailure
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Test

class SpinLockTest {

    fun spinLock(): Int {
        var counter = 0
        val lock = SpinLock()
        val t1 = thread {
            lock.withLock { counter++ }
        }
        val t2 = thread {
            lock.withLock { counter++ }
        }
        val t3 = thread {
            lock.withLock { counter++ }
        }
        return lock.withLock { counter }
            .also { listOf(t1, t2, t3).forEach { it.join() } }
    }

    @Test(timeout = TIMEOUT)
    fun testSpinLock() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::spinLock,
        expectedOutcomes = setOf(0, 1, 2, 3),
    )

}

class SpinLockLivelockIsolatedTest {

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
        t1.join()
        t2.join()
        return counter
    }

    @Test(timeout = TIMEOUT)
    fun testLivelock() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::livelock,
        expectedFailure = ManagedDeadlockFailure::class,
    )

}

internal class SpinLock {
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

internal fun <R> SpinLock.withLock(block: () -> R): R {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}