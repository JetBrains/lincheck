/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.channel06

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

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

suspend fun performOperation(
    producer: Producer,
    consumer: Consumer,
    processor: Processor,
    dispatcher: CoroutineDispatcher
) = coroutineScope {
    launch(dispatcher) { producer.produce() }
    launch(dispatcher) { processor.process() }
    launch(dispatcher) { consumer.consume() }
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val channel1 = Channel<Int>()
    val channel2 = Channel<Int>()

    val producer = Producer(channel1)
    val consumer = Consumer(channel2)
    val processor = Processor(channel1, channel2)

    launch(dispatcher) { performOperation(producer, consumer, processor, dispatcher) }
    launch(dispatcher) { performOperation(producer, consumer, processor, dispatcher) }
    launch(dispatcher) { performOperation(producer, consumer, processor, dispatcher) }
}

class ChannelTest06 : BaseChannelTest(true) {
    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}