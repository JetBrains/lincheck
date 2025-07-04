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
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class CoroutineCancelTest : FixedThreadPoolCoroutineTest() {

    @Test
    fun testCancel() = executeCoroutineTest { dispatcher ->
        runBlocking(dispatcher) {
            val senderJob = Job()
            val started = AtomicBoolean(false)
            val flag = AtomicBoolean(false)

            val sender = launch(dispatcher) {
                try {
                    started.set(true)
                    while (isActive) { /* do nothing */ }
                } finally {
                    flag.set(true)
                    senderJob.cancel()
                }
            }

            while (!started.get()) { /* wait for sender to start */ }
            sender.cancel()
            sender.join()
            check(flag.get())
            senderJob.join() // should not hang
        }
    }

    @Test
    fun testCancelAndJoin() = executeCoroutineTest { dispatcher ->
        runBlocking(dispatcher) {
            val coro = launch(dispatcher) {
                while (isActive) { /* do nothing */ }
            }
            coro.cancel()
            coro.join()
        }
    }

    @Test
    fun testCancelJoin() = executeCoroutineTest { dispatcher ->
        runBlocking(dispatcher) {
            val coro = launch(dispatcher) {
                while (isActive) { /* do nothing */ }
            }
            coro.cancelAndJoin()
        }
    }

    @Test
    fun testManualStop() = executeCoroutineTest { dispatcher ->
        runBlocking(dispatcher) {
            val flag = AtomicBoolean(false)
            val coro = launch(dispatcher) {
                while (!flag.get()) { /* do nothing */ }
            }
            flag.set(true)
            coro.join()
            check(flag.get())
        }
    }
}