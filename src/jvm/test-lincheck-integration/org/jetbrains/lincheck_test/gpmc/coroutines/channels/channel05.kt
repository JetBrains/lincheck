/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.gpmc.coroutines.channels.channel05

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class Processor {
    val channel1 = Channel<Int>()

    suspend fun produceNumbers() {
        for (i in 1..5) {
            channel1.send(i)
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
        results.compute(y) { _, v -> if (v == null) 1 else v + 1 }
    }
    check(
        results.entries.map { Pair(it.key, it.value) }.containsAll(listOf(
            Pair(2, 1),
            Pair(4, 1),
            Pair(6, 1),
            Pair(8, 1),
            Pair(10, 1)
        ))
    )
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val processor = Processor()
    val aggregator = Aggregator()

    launch(dispatcher) { processor.produceNumbers() }
    launch(dispatcher) { aggregator.aggregateNumbers(processor) }
    launch(dispatcher) { aggregator.sendToChannels() }

    launch(dispatcher) { receive(aggregator.channel4) }
    launch(dispatcher) { receive(aggregator.channel5) }
}

class ChannelTest05 : BaseChannelTest() {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}