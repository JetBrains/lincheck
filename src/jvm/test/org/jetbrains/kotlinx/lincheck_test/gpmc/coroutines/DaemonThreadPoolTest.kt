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
import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(ExperimentalModelCheckingAPI::class, DelicateCoroutinesApi::class)
class DaemonThreadPoolTest {
    //@Ignore("Daemon threads are treated as active, see https://github.com/JetBrains/lincheck/issues/542")
    @Test
    fun testUselessThreadPool() {
        runConcurrentTest(1000) {
            val pool = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
            runBlocking(pool) { /* do nothing */ }
            // pool.close() -- should not be required
        }
    }

    @Test
    fun testInfiniteCoroutineAsDaemon() {
        runConcurrentTest(1000) {
            val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
            var x = 0
            GlobalScope.launch(pool) {
                // 1. coroutine has no parent job
                // 2. coroutine uses thread from the `pool`
                while (true) x++
            }
        }
    }
}