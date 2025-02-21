/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test917
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test917.RunChecker917.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Ignore

class MessageSender(private val channel: Channel<Int>) {
    suspend fun sendMessage(message: Int) {
        channel.send(message)
    }
}

fun CoroutineScope.setupCoroutines(channel: Channel<Int>) {
    launch(pool) {
        channel.receive()
    }
    launch(pool) {
        channel.receive()
    }
    launch(pool) {
        channel.receive()
    }
    launch(pool) {
        channel.receive()
    }
    launch(pool) {
        channel.receive()
    }
}

fun main(): Unit = runBlocking(pool) {
    val channel = Channel<Int>()
    val messageSender = MessageSender(channel)
    
    setupCoroutines(channel)
    
    messageSender.sendMessage(1)
    messageSender.sendMessage(2)
    messageSender.sendMessage(3)
    messageSender.sendMessage(4)
    messageSender.sendMessage(5)
}

class RunChecker917 : BaseRunCoroutineTests(false, 1000) {
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