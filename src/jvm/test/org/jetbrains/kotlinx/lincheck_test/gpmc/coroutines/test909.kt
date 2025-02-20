/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test909
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test909.RunChecker909.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class SimpleClass(val channel: Channel<Int>) {
    suspend fun sendValue(value: Int) {
        channel.send(value)
    }
}

fun main(): Unit = runBlocking(pool) {
    val channel = Channel<Int>()

    val simpleClass = SimpleClass(channel)

    launch(pool) {
        simpleClass.sendValue(1)
    }

    launch(pool) {
        delay(100)
        channel.receive()
    }

    launch(pool) {
        val received = channel.receive()
        check(received == 1)
    }
}

class RunChecker909 : BaseRunCoroutineTests(true) {
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