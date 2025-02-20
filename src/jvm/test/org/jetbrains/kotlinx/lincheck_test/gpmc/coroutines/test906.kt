/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test906
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test906.RunChecker906.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class Producer(val channel: Channel<Int>) {
    suspend fun produce() {
        for (i in 1..5) {
            channel.send(i)
        }
    }
}

class Consumer(val channel: Channel<Int>) {
    suspend fun consume() {
        for (i in 1..5) {
            channel.receive()
        }
    }
}

class Processor(val channel1: Channel<Int>, val channel2: Channel<Int>) {
    suspend fun process() {
        val value = channel1.receive()
        channel2.send(value)
    }
}

suspend fun performOperation(producer: Producer, consumer: Consumer, processor: Processor) = coroutineScope {
    launch(pool) { producer.produce() }
    launch(pool) { processor.process() }
    launch(pool) { consumer.consume() }
}

fun main(): Unit = runBlocking(pool) {
    val channel1 = Channel<Int>()
    val channel2 = Channel<Int>()

    val producer = Producer(channel1)
    val consumer = Consumer(channel2)
    val processor = Processor(channel1, channel2)

    launch(pool) { performOperation(producer, consumer, processor) }
    launch(pool) { performOperation(producer, consumer, processor) }
    launch(pool) { performOperation(producer, consumer, processor) }
}

class RunChecker906 : BaseRunCoroutineTests(true) {
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