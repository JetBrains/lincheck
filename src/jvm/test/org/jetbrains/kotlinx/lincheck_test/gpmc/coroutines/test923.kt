/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test923
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test923.RunChecker923.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class Worker {
    suspend fun process(ch1: Channel<Int>, ch2: Channel<Int>) {
        for (i in 1..5) {
            val received = ch1.receive()
            ch2.send(received * 2)
        }
    }
}

suspend fun sender(ch: Channel<Int>) {
    repeat(5) {
        ch.send(it + 1)
    }
}

suspend fun receiver(ch: Channel<Int>) {
    repeat(5) {
        println(ch.receive())
    }
}

fun main(): Unit = runBlocking(pool) {
    val ch1 = Channel<Int>()
    val ch2 = Channel<Int>()
    val worker = Worker()

    launch(pool) { sender(ch1) }
    launch(pool) { worker.process(ch1, ch2) }
    launch(pool) { receiver(ch2) }
}

class RunChecker923: BaseRunCoroutineTests(false) {
        companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        runBlocking(pool) { main() }
    }
}