/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.transformation

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.junit.Test

// tests wait/notify support in model checking strategy
class WaitNotifyLockTest {
    private var counter = 0
    private val lock = WaitNotifyLock()

    @Operation
    fun getAndIncrement() = lock.withLock { counter++ }

    @Test
    fun test() {
        val opts = ModelCheckingOptions()
            .iterations(30)
        opts.check(this::class)
    }
}

// tests wait/notify support under lock re-entrance in model checking strategy
class NestedWaitNotifyLockTest {
    private var counter = 0
    private val lock = NestedWaitNotifyLock()

    @Operation
    fun getAndIncrement() = lock.withLock { counter++ }

    @Test
    fun test() {
        val opts = ModelCheckingOptions()
            .iterations(30)
        opts.check(this::class)
    }
}

private interface SimpleLock {
    fun lock()
    fun unlock()
}

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private class WaitNotifyLock : SimpleLock {
    private var owner: Thread? = null

    override fun lock() {
        val thread = Thread.currentThread()
        synchronized(this) {
            while (owner != null) {
                (this as Object).wait()
            }
            owner = thread
        }
    }

    override fun unlock() {
        synchronized(this) {
            owner = null
            (this as Object).notify()
        }
    }
}

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private class NestedWaitNotifyLock : SimpleLock {
    private var owner: Thread? = null

    override fun lock() {
        val thread = Thread.currentThread()
        synchronized(this) {
            synchronized(this) {
                while (owner != null) {
                    (this as Object).wait()
                }
                owner = thread
            }
        }
    }

    override fun unlock() {
        synchronized(this) {
            synchronized(this) {
                owner = null
                (this as Object).notify()
            }
        }
    }
}

private inline fun <T> SimpleLock.withLock(body: () -> T): T {
    try {
        lock()
        return body()
    } finally {
        unlock()
    }
}