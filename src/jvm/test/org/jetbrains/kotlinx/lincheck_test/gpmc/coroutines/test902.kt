/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test902
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test902.RunChecker902.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.*
import org.junit.Ignore
import org.junit.Test

class Producer1(private val outChannel: Channel<Int>) {
    suspend fun produce() {
        for (i in 1..5) {
            outChannel.send(i)
            delay(100)
        }
        outChannel.close()
    }
}

class Producer2(private val outChannel: Channel<Int>) {
    suspend fun produce() {
        for (i in 6..10) {
            outChannel.send(i)
            delay(150)
        }
        outChannel.close()
    }
}

class Consumer(private val inChannel1: Channel<Int>, private val inChannel2: Channel<Int>, private val outChannel: Channel<Int>) {
    suspend fun consume() {
        for (element1 in inChannel1) {
            outChannel.send(element1 * 2)
        }
        for (element2 in inChannel2) {
            outChannel.send(element2 * 2)
        }
        outChannel.close()
    }
}

suspend fun relay(from: Channel<Int>, to: Channel<Int>) {
    for (element in from) {
        to.send(element)
    }
    to.close()
}

fun main(): Unit = runBlocking(pool) {
    val producerChannel1 = Channel<Int>()
    val producerChannel2 = Channel<Int>()
    val consumerChannel1 = Channel<Int>()
    val consumerChannel2 = Channel<Int>()
    val resultChannel = Channel<Int>()

    val producer1 = Producer1(producerChannel1)
    val producer2 = Producer2(producerChannel2)
    val consumer = Consumer(consumerChannel1, consumerChannel2, resultChannel)

    launch(pool) { producer1.produce() }
    launch(pool) { producer2.produce() }
    launch(pool) { relay(producerChannel1, consumerChannel1) }
    launch(pool) { relay(producerChannel2, consumerChannel2) }
    launch(pool) { consumer.consume() }

    for (result in resultChannel) {
        check(result % 2 == 0 && (result / 2) in 1..10)
    }
}

@Ignore("'All unfinished threads are in deadlock' but should finish")
class RunChecker902: BaseRunCoroutineTests(false) {
    companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        runBlocking(pool) { main() }
    }
}