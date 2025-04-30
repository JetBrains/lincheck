/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread


@OptIn(ExperimentalModelCheckingAPI::class)
class DaemonThreadPoolTest {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    @Test
    fun testUselessThreadPool() {
        runConcurrentTest(1000) {
            val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
            runBlocking(pool) { /* do nothing */ }
            // pool.close() -- we do not need this call, because lincheck will
            //                 abort background threads, when main is finished
        }
    }

    @Test
    fun testUselessThreadPoolWithMultipleCoroutines() {
        runConcurrentTest(1000) {
            val pool = Executors.newFixedThreadPool(5).asCoroutineDispatcher()
            runBlocking(pool) {
                val c1 = launch(pool) {}
                val c2 = launch(pool) {}
                val c3 = launch(pool) {}
                val c4 = launch(pool) {}
                val c5 = launch(pool) {}

                c1.join()
                c2.join()
                c3.join()
                c4.join()
                c5.join()
            }
            // pool.close() -- no manual closing
        }
    }

    @Test
    fun testInfiniteBackgroundThreads() {
        runConcurrentTest(1000) {
            val atomic = AtomicInteger(0)
            thread {
                while (true) atomic.incrementAndGet()
            }
            thread(isDaemon = true) {
                while (true) atomic.decrementAndGet()
            }
        }
    }

    @Test
    fun testManyInfiniteBackgroundThreads() {
        runConcurrentTest(1000) {
            val atomic = AtomicInteger(0)
            thread {
                while (true) atomic.incrementAndGet()
            }
            thread {
                while (true) atomic.incrementAndGet()
            }
            thread {
                while (true) atomic.incrementAndGet()
            }
            thread {
                while (true) atomic.incrementAndGet()
            }
        }
    }

    @Test(expected = LincheckAssertionError::class)
    fun testBackgroundThreadsDeadlock() {
        runConcurrentTest(1000) {
            val a = AtomicInteger(0)
            val sync1 = Any()
            val sync2 = Any()

            thread {
                synchronized(sync1) {
                    synchronized(sync2) {
                        a.incrementAndGet()
                    }
                }
            }
            thread {
                synchronized(sync2) {
                    synchronized(sync1) {
                        a.incrementAndGet()
                    }
                }
            }

            check(a.get() in (0..2))
        }
    }

    @Test(expected = LincheckAssertionError::class)
    fun testBackgroundCoroutinesDeadlock() {
        runConcurrentTest(10000) {
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { pool ->
                val m = Mutex()
                runBlocking(pool) {
                    launch(pool) {
                        m.lock()
                    }
                    launch(pool) {
                        m.lock()
                    }
                }
            }
        }
    }

    @Test
    fun testJavaThreadPoolFutures() {
        runConcurrentTest(1000) {
            val a = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(3)
            val f1 = pool.submit { a.incrementAndGet() }
            val f2 = pool.submit { a.incrementAndGet() }
            val f3 = pool.submit { a.incrementAndGet() }

            f1.get()
            f2.get()
            f3.get()
            // no explicit shutdown
        }
    }

    @Test
    fun testJavaThreadPoolAsyncTasks() {
        runConcurrentTest(1000) {
            val a = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(3)
            // fire and forget
            pool.execute { a.incrementAndGet() }
            pool.execute { a.incrementAndGet() }
            pool.execute { a.incrementAndGet() }
            // no explicit shutdown
        }
    }
}