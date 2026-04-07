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

import org.jetbrains.lincheck.Lincheck.runConcurrentTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread


class UserThreadExceptionTest {

    @Test
    fun testUserThreadException() {
        runConcurrentTest {
            val t = thread {
                check(false)
            }
            t.join()
        }
    }

    @Test
    fun testExceptionInInnerUserThread() {
        runConcurrentTest {
            val t = thread {
                val inner = thread {
                    check(false)
                }

                inner.join()
            }

            t.join() // should join with no problems
        }
    }

    @Test
    fun testUnhandledExceptionCallback() {
        runConcurrentTest {
            val t = thread(start = false) {
                throw RuntimeException("Some exception")
            }
            t.setUncaughtExceptionHandler { _, err ->
                check(err is RuntimeException)
                // then just do nothing
            }
            t.start()
            t.join() // should join with no problems
        }
    }

    @Test
    fun testFinallyBlockInUserThread() {
        runConcurrentTest {
            val x = AtomicInteger()
            val t = thread {
                try {
                    check(false)
                }
                finally {
                    // Finally block will be executed before this thread
                    // will be finished by the strategy, which is expected.
                    // Because strategy wraps the `Thread::run` method in
                    // `try {} finally {}` which is the most outer
                    // try-finally block in the thread body
                    x.incrementAndGet()
                }
            }

            t.join()
            check(x.get() == 1)
        }
    }
}