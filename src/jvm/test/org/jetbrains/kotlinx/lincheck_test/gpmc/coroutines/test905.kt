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
import org.junit.Ignore

class Processor {
    val channel1 = Channel<Int>()

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

suspend fun receive(channel: Channel<Int>) {
    val results = mutableMapOf<Int, Int>()
    for (y in channel) {
        //println("Received: $y")
        results.compute(y) { _, v -> if (v == null) 1 else v + 1 }
    }
    results.entries.map { Pair(it.key, it.value) }.containsAll(listOf(
        Pair(2, 1),
        Pair(4, 1),
        Pair(6, 1),
        Pair(8, 1),
        Pair(10, 1)
    ))
}

fun main(): Unit = runBlocking(pool) {
    val processor = Processor()
    val aggregator = Aggregator()

    launch(pool) { processor.produceNumbers() }
    launch(pool) { aggregator.aggregateNumbers(processor) }
    launch(pool) { aggregator.sendToChannels() }

    launch(pool) { receive(aggregator.channel4) }
    launch(pool) { receive(aggregator.channel5) }
}

@Ignore("'All unfinished threads are in deadlock' but should finish")
class RunChecker905 : BaseRunCoroutineTests(false) {
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