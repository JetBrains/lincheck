/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test903
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test903.RunChecker903.Companion.pool
import java.util.concurrent.Executors

@OptIn(DelicateCoroutinesApi::class)
fun producerA(channelA: Channel<Int>) = GlobalScope.launch(pool) {
    repeat(5) {
        channelA.send(it)
    }
    channelA.close()
}

@OptIn(DelicateCoroutinesApi::class)
fun producerB(channelB: Channel<Int>, channelA: Channel<Int>) = GlobalScope.launch(pool) {
    repeat(5) {
        val value = channelA.receive() * 2
        channelB.send(value)
    }
    channelB.close()
}

fun main(): Unit = runBlocking(pool) {
    val channelA = Channel<Int>()
    val channelB = Channel<Int>()

    producerA(channelA)
    producerB(channelB, channelA)

    repeat(5) {
        launch(pool) {
            check(channelB.receive() % 2 == 0)
        }
    }
}

class RunChecker903 : BaseRunCoroutineTests(false, 1000) {
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