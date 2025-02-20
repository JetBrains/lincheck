/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test911
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test911.RunChecker911.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Ignore

class Producer {
    suspend fun produce(channel: Channel<Int>) {
        channel.send(1)  // Sends value 1 to the channel
    }
}

class Consumer {
    suspend fun consume(channel: Channel<Int>) {
        channel.receive()  // Tries to receive a value from the channel
    }
}

fun main(): Unit = runBlocking(pool) {
    val channel = Channel<Int>()
    val producer = Producer()
    val consumer = Consumer()

    // Coroutine 1: Producer sends a value to the channel
    launch(pool) {
        producer.produce(channel)
    }

    // Coroutine 2: Consumer tries to receive a value from the channel
    launch(pool) {
        consumer.consume(channel)
    }
}

@Ignore("""
java.lang.IllegalStateException: Check failed.
	at org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy.runInvocation(ManagedStrategy.kt:245)
""")
class RunChecker911 : BaseRunCoroutineTests(false) {
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