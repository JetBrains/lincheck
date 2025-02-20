/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test901
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test901.RunChecker901.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class ChannelHandler {
    val channel1 = Channel<Int>()
    val channel2 = Channel<Int>()

    suspend fun sendToChannel1() {
        channel1.send(1)
        receiveFromChannel2()
    }

    suspend fun receiveFromChannel2() {
        check(channel2.receive() == 2)
    }

    suspend fun sendToChannel2() {
        channel2.send(2)
        receiveFromChannel1()
    }

    suspend fun receiveFromChannel1() {
        check(channel1.receive() == 1)
    }
}

fun main(): Unit = runBlocking(pool) {
    val handler = ChannelHandler()

    launch(pool) {
        handler.sendToChannel1()
    }
    handler.sendToChannel2()
}

class RunChecker901: BaseRunCoroutineTests(true) {
    companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        pool.use {
            runBlocking(pool) { main() }
        }
    }
}