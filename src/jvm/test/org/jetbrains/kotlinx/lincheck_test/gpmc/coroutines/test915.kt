/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test915
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test915.RunChecker915.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class Producer(private val channel: Channel<Int>) {
    suspend fun produce() {
        repeat(5) {
            channel.send(it)
        }
        channel.close()
    }
}

class Consumer(private val channel: Channel<Int>) {
    suspend fun consume() {
        for (item in channel) {
            processData(item)
        }
    }

    private fun processData(data: Int) {
        println(data)
    }
}

suspend fun startProducer(producer: Producer) = coroutineScope {
    launch(pool) {
        producer.produce()
    }
}

suspend fun startConsumer(consumer: Consumer) = coroutineScope {
    launch(pool) {
        consumer.consume()
    }
}

fun main(): Unit = runBlocking(pool) {
    val channel = Channel<Int>()
    val producer = Producer(channel)
    val consumer = Consumer(channel)

    startProducer(producer)
    startConsumer(consumer)
    delay(1000)  // Give some time for coroutines to finish
}

class RunChecker915 : BaseRunCoroutineTests(true) {
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