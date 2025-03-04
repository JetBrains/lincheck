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

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(ExperimentalModelCheckingAPI::class)
class DaemonThreadPoolTest : BaseCoroutineTest() {

    @Ignore("Daemon threads are treated as active, see https://github.com/JetBrains/lincheck/issues/542")
    @Test
    fun testUselessThreadPool() {
        val iterations = 1000
        runConcurrentTest(iterations) {
            val pool = createDispatcher()
            runBlocking(pool) { /* do nothing */ }
            // pool.close() -- this call tells gpmc that threads are not in infinite spinning,
            //                 but interleavings exploration still happens
        }
    }

    override fun createDispatcher() = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
}