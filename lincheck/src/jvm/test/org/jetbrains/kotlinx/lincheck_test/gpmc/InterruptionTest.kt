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

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import org.junit.Test

class InterruptionTest {

    fun isInterrupted(): Boolean {
        var wasInterrupted = false
        var counter = AtomicInteger(0)

        val t = thread {
            while (!Thread.currentThread().isInterrupted) {
                counter.incrementAndGet()
            }
            check(Thread.currentThread().isInterrupted)
            wasInterrupted = true
        }
        t.interrupt()
        t.join()

        return wasInterrupted
    }

    @Test(timeout = TIMEOUT)
    fun isInterruptedTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::isInterrupted,
        expectedOutcomes = setOf(true),
    )

    fun interrupted(): Boolean {
        var wasInterrupted = false
        var counter = AtomicInteger(0)

        val t = thread {
            while (!Thread.interrupted()) {
                counter.incrementAndGet()
            }
            check(!Thread.currentThread().isInterrupted)
            wasInterrupted = true
        }
        t.interrupt()
        t.join()

        return wasInterrupted
    }

    @Test(timeout = TIMEOUT)
    fun interruptedTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::interrupted,
        expectedOutcomes = setOf(true),
    )

    fun interruptBusyLoop(): Boolean {
        var wasInterrupted = false
        var counter = AtomicInteger(0)

        val t = thread {
            while (!Thread.interrupted()) {
                counter.incrementAndGet()
            }
            wasInterrupted = true
        }
        t.interrupt()
        t.join()
        return wasInterrupted
    }

    @Test(timeout = TIMEOUT)
    fun interruptBusyLoopTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::interruptBusyLoop,
        expectedOutcomes = setOf(true),
    )

    fun interruptWait(): Boolean {
        var wasInterrupted = false
        var counter = AtomicInteger(0)
        val lock = Any()

        val t = thread {
            try {
                synchronized(lock) {
                    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                    (lock as Object).wait()
                    counter.incrementAndGet()
                }
            } catch (e: InterruptedException) {
                wasInterrupted = true
            }
        }
        t.interrupt()
        t.join()

        return wasInterrupted
    }

    @Test(timeout = TIMEOUT)
    fun interruptWaitTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::interruptWait,
        expectedOutcomes = setOf(true),
    )

    fun uncaughtInterruptedException() {
        var counter = AtomicInteger(0)
        val lock = Any()

        val t = thread {
            synchronized(lock) {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (lock as Object).wait()
                counter.incrementAndGet()
            }
        }
        t.interrupt()
        t.join()
    }

    @Test(timeout = TIMEOUT)
    fun uncaughtInterruptedExceptionTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::uncaughtInterruptedException,
    )

    fun interruptJoin(): Boolean {
        var wasInterrupted = false
        var counter = AtomicInteger(0)

        val incrementer = thread {
            while (!Thread.interrupted()) {
                counter.incrementAndGet()
            }
        }
        val joiner = thread {
            try {
                incrementer.join()
            } catch (e: InterruptedException) {
                wasInterrupted = true
            }
        }

        joiner.interrupt()
        joiner.join()

        // Make sure we clean up the incrementer thread
        incrementer.interrupt()
        incrementer.join()

        return wasInterrupted
    }

    @Test(timeout = TIMEOUT)
    fun interruptJoinTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::interruptJoin,
        expectedOutcomes = setOf(true),
    )

    fun interruptParkLoop(): Boolean {
        var wasInterrupted = false
        var counter = AtomicInteger(0)

        val t = thread {
            while (true) {
                LockSupport.park()
                counter.incrementAndGet()
                wasInterrupted = Thread.interrupted()
                if (wasInterrupted) break
            }
        }
        t.interrupt()
        t.join()

        return wasInterrupted
    }

    @Test(timeout = TIMEOUT)
    fun interruptParkLoopTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::interruptParkLoop,
        expectedOutcomes = setOf(true),
    )

    fun interruptReentrantLock(): Boolean {
        var wasInterrupted = false
        var counter = AtomicInteger(0)
        val lock = ReentrantLock()

        lock.withLock {
            val t = thread {
                try {
                    lock.lockInterruptibly()
                    counter.incrementAndGet()
                } catch (e: InterruptedException) {
                    wasInterrupted = true
                } finally {
                    if (!wasInterrupted) {
                        lock.unlock()
                    }
                }
            }
            t.interrupt()
            t.join()
        }

        return wasInterrupted
    }

    @Test(timeout = TIMEOUT)
    fun interruptReentrantLockTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::interruptReentrantLock,
        expectedOutcomes = setOf(true),
    )

    fun multipleInterrupts(): Int {
        var interruptCount = AtomicInteger(0)
        var counter = AtomicInteger(0)
        val lock = Any()

        val t = thread {
            for (i in 1..3) {
                try {
                    synchronized(lock) {
                        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                        (lock as Object).wait()
                        counter.incrementAndGet()
                    }
                } catch (e: InterruptedException) {
                    interruptCount.incrementAndGet()
                    // check that interrupt status is reset
                    check(!Thread.currentThread().isInterrupted)
                }
            }
        }
        for (i in 1..3) {
            t.interrupt()
            while (interruptCount.get() < i) {
                Thread.yield()
            }
        }
        t.join()

        return interruptCount.get()
    }

    @Test(timeout = TIMEOUT)
    fun multipleInterruptsTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::multipleInterrupts,
        expectedOutcomes = setOf(3),
    )

    fun delayedInterruptHandling(): Boolean {
        var wasInterrupted = false
        val lock = Any()

        val t = thread {
            // Set the interrupt flag but don't immediately handle it
            Thread.currentThread().interrupt()

            // Later try an interruptible operation
            try {
                synchronized(lock) {
                    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                    (lock as Object).wait()
                }
            } catch (e: InterruptedException) {
                check(!Thread.currentThread().isInterrupted)
                wasInterrupted = true
            }
        }
        t.join()

        return wasInterrupted
    }

    @Test(timeout = TIMEOUT)
    fun delayedInterruptHandlingTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::delayedInterruptHandling,
        expectedOutcomes = setOf(true),
    )

}