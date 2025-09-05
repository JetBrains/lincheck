/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:Suppress("DEPRECATION")
package org.jetbrains.kotlinx.lincheck_test.transformation

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest
import org.junit.Test

// tests wait/notify support in model checking strategy
@ModelCheckingCTest(iterations = 30)
class WaitNotifyLockTest {
    private var counter = 0
    private val lock = WaitNotifyLock()

    @Operation
    fun getAndIncrement() = lock.withLock { counter++ }

    @Test
    fun test() {
        @Suppress("DEPRECATION")
        LinChecker.check(this::class.java)
    }
}

// tests wait/notify support under lock re-entrance in model checking strategy
@ModelCheckingCTest(iterations = 30)
class NestedWaitNotifyLockTest {
    private var counter = 0
    private val lock = NestedWaitNotifyLock()

    @Operation
    fun getAndIncrement() = lock.withLock { counter++ }

    @Test
    fun test() {
        @Suppress("DEPRECATION")
        LinChecker.check(this::class.java)
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