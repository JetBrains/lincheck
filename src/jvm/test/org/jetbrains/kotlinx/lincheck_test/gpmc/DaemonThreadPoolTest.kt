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

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(ExperimentalModelCheckingAPI::class)
class DaemonThreadPoolTest {
    @Test
    fun testUselessThreadPool() {
        runConcurrentTest(1000) {
            val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
            runBlocking(pool) { /* do nothing */ }
            // pool.close() -- this call tells gpmc that threads are not in infinite spinning,
            //                 but interleavings exploration still happens
        }
    }
}