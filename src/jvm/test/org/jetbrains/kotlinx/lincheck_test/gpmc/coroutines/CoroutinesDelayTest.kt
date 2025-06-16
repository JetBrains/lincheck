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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck.Lincheck
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class CoroutinesDelayTest {
    @Test
    fun testDelay() {
        Lincheck.runConcurrentTest(invocations = 1) {
            Executors.newFixedThreadPool(1).asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    val nElements = 2
                    val channel = Channel<Int>() // RENDEZVOUS

                    launch(dispatcher) {
                        for (i in 1..nElements) {
                            channel.send(i)
                            delay(100)
                            delay(100.toDuration(DurationUnit.MILLISECONDS))
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