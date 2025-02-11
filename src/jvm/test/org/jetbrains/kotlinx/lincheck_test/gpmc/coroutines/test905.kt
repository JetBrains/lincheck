/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test905
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test905.RunChecker905.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

class Processor {
    val channel1 = Channel<Int>()
    val channel2 = Channel<Int>()

    suspend fun produceNumbers() {
        for (i in 1..5) {
            channel1.send(i)
            delay(10)
        }
        channel1.close()
    }
}

class Aggregator {
    val channel3 = Channel<Int>()
    val channel4 = Channel<Int>()
    val channel5 = Channel<Int>(2)

    suspend fun aggregateNumbers(processor: Processor) {
        for (x in processor.channel1) {
            val result = x * 2
            channel3.send(result)
        }
        channel3.close()
    }

    suspend fun sendToChannels() {
        for (x in channel3) {
            channel4.send(x)
            channel5.send(x)
        }
        channel4.close()
        channel5.close()
    }
}

suspend fun receiveAndPrint(channel: Channel<Int>) {
    for (y in channel) {
        println("Received: $y")
    }
}

fun main(): Unit = runBlocking(pool) {
    val processor = Processor()
    val aggregator = Aggregator()

    launch(pool) { processor.produceNumbers() }
    launch(pool) { aggregator.aggregateNumbers(processor) }
    launch(pool) { aggregator.sendToChannels() }

    launch(pool) { receiveAndPrint(aggregator.channel4) }
    launch(pool) { receiveAndPrint(aggregator.channel5) }
}

class RunChecker905: BaseRunCoroutineTests(false) {
        companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        runBlocking(pool) { main() }
    }
}