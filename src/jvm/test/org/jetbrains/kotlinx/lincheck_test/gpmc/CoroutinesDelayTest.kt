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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(ExperimentalModelCheckingAPI::class)
class CoroutinesDelayTest {
    @Ignore("Delay (even 1ms) in coroutine puts all threads in deadlock")
    @Test
    fun test() {
        runConcurrentTest(1000) {
            val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
            pool.use {
                runBlocking(pool) {
                    val nElements = 2
                    val channel = Channel<Int>() // RENDEZVOUS

                    launch(pool) {
                        for (i in 1..nElements) {
                            channel.send(i)
                            delay(1)
                        }
                        channel.close()
                    }

                    for (i in 1..nElements) {
                        val element = channel.receive()
                        check(element == i)
                    }
                }
            }
        }
    }
}