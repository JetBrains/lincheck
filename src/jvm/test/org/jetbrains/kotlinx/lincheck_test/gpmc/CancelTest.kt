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

import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.launch
import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.junit.Ignore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalModelCheckingAPI::class)
@Suppress("UNUSED_VARIABLE", "DEPRECATION")
class CancelTest {
    var flag = AtomicBoolean(false)
    var spins = 0

    @Test
    fun testCancel() {
        runConcurrentTest(1) {
            flag.set(false)
            spins = 0
            val pool = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
            runBlocking {
                //val senderJob = Job()
                val senders = launch(pool) {
                    try {
                        while (isActive) {
                            // do something
                            spins++
                        }
                    } finally {
                        flag.set(true)
                        //senderJob.cancel()
                    }
                }

                senders.cancel()
                senders.join()
                while (!flag.get()) {}
                //senderJob.join()
                //println(spins)
            }
            pool.close()
        }
    }

    //@Ignore("Invalid thread switch (internal lincheck error)")
    @Test
    fun testCancelAndJoin() = runConcurrentTest(10000) {
        runBlocking {
            val pool = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
            val coro = launch(pool + CoroutineName("Coro")) {
                while (isActive) {}
            }

            coro.cancel()
            coro.join()
            pool.close()
        }
    }

    //@Ignore("= Concurrent test has hung =")
    @Test
    fun testCancelJoin() = runConcurrentTest(1) {
        runBlocking {
            val pool = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
            val coro = launch(pool + CoroutineName("Coro")) {
                while (isActive) {}
            }

            coro.cancelAndJoin()
            pool.close()
        }
    }

    //@Ignore("Invalid thread switch (internal lincheck error)")
    @Test
    fun testManualStop() = runConcurrentTest(10000 /* smaller might pass */) {
        runBlocking {
            val flag = AtomicBoolean(false)
            val pool = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
            var inc = 0
            val coro = launch(pool + CoroutineName("Coro")) {
                while (!flag.get()) {
                    inc++
                }
            }

            flag.set(true)
            coro.join()
            check(inc >= 0)
            pool.close()
        }
    }
}