/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test919
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test919.RunChecker919.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

fun main(): Unit = runBlocking(pool) {
    val channel1 = Channel<Int>()
    val channel2 = Channel<Int>()
    val channel3 = Channel<Int>()
    val channel4 = Channel<Int>()
    val channel5 = Channel<Int>()

    launch(pool) { coroutine1(channel1, channel2) }
    launch(pool) { coroutine2(channel2, channel3) }
    launch(pool) { coroutine3(channel3, channel4) }
    launch(pool) { coroutine4(channel5, channel1) }
}

suspend fun coroutine1(ch1: Channel<Int>, ch2: Channel<Int>) {
    ch1.send(1)
    ch2.receive()
}

suspend fun coroutine2(ch2: Channel<Int>, ch3: Channel<Int>) {
    ch3.send(2)
    ch2.receive()
}

suspend fun coroutine3(ch3: Channel<Int>, ch4: Channel<Int>) {
    ch4.send(3)
    ch3.receive()
}

suspend fun coroutine4(ch5: Channel<Int>, ch1: Channel<Int>) {
    ch1.receive()
    ch5.send(4)
}

class RunChecker919: BaseRunCoroutineTests(true) {
        companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        runBlocking(pool) { main() }
    }
}